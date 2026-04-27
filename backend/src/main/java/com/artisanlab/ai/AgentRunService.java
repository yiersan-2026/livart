package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentRunService {
    private static final Pattern PLACEMENT_INTENT_PATTERN = Pattern.compile(
            "放在|放到|放入|放进|放置|摆在|摆到|置于|添加到|放上|摆放|穿到|穿在|穿上|戴到|戴在|装到|装在|应用到|应用在"
    );

    private final AgentPlannerService agentPlannerService;
    private final AiProxyService aiProxyService;
    private final ImageJobEventBroadcaster eventBroadcaster;

    public AgentRunService(
            AgentPlannerService agentPlannerService,
            AiProxyService aiProxyService,
            ImageJobEventBroadcaster eventBroadcaster
    ) {
        this.agentPlannerService = agentPlannerService;
        this.aiProxyService = aiProxyService;
        this.eventBroadcaster = eventBroadcaster;
    }

    public AiProxyDtos.AgentRunResponse run(UUID userId, AiProxyDtos.AgentRunRequest request) throws IOException {
        String runId = normalizeClientRunId(request.clientRunId());
        publishProgress(userId, runId, "identify-intent", "识别意图", "判断你是在问 livart 功能，还是要生成 / 编辑图片。", "analysis", "running");

        AiProxyDtos.AgentPlanResponse plan;
        try {
            plan = agentPlannerService.createPlan(userId, request.toPlanRequest());
            publishProgress(userId, runId, "identify-intent", "识别意图", "已完成意图识别。", "analysis", "completed");
        } catch (RuntimeException exception) {
            publishProgress(userId, runId, "identify-intent", "识别意图", "意图识别失败。", "analysis", "error");
            throw exception;
        }

        if (!"execute".equals(plan.responseMode()) || !plan.allowed()) {
            if ("answer".equals(plan.responseMode())) {
                publishProgress(userId, runId, "knowledge-answer", "检索知识库", "已根据 livart 系统知识整理回答。", "analysis", "completed");
            } else {
                publishProgress(userId, runId, "scope-check", "检查范围", "当前请求不属于 livart 可处理范围。", "analysis", "completed");
            }
            return responseWithoutJobs(plan, request.prompt());
        }

        publishProgress(userId, runId, "plan-task", "规划任务", "已规划公开执行步骤，准备创建图片任务。", "analysis", "completed");
        publishProgress(userId, runId, "create-image-job", "提交任务", "正在提交图片生成任务。", "generate", "running");
        try {
            AiProxyDtos.AgentRunResponse response = "text-to-image".equals(plan.taskType())
                    ? runTextToImage(userId, request, plan)
                    : runImageEdit(userId, request, plan);
            publishProgress(userId, runId, "create-image-job", "提交任务", "图片任务已创建，接下来等待生成结果。", "generate", "completed");
            return response;
        } catch (IOException | RuntimeException exception) {
            publishProgress(userId, runId, "create-image-job", "提交任务", "图片任务创建失败。", "generate", "error");
            throw exception;
        }
    }

    private AiProxyDtos.AgentRunResponse runTextToImage(
            UUID userId,
            AiProxyDtos.AgentRunRequest request,
            AiProxyDtos.AgentPlanResponse plan
    ) throws IOException {
        List<AiProxyDtos.AgentRunJob> jobs = new ArrayList<>();
        int count = normalizeCount(plan.count());
        aiProxyService.createTextToImageJobsFromAgent(userId, request.prompt(), plan.aspectRatio(), count)
                .forEach(job -> jobs.add(toRunJob(job)));

        return responseWithJobs(plan, request.prompt(), jobs);
    }

    private AiProxyDtos.AgentRunResponse runImageEdit(
            UUID userId,
            AiProxyDtos.AgentRunRequest request,
            AiProxyDtos.AgentPlanResponse plan
    ) throws IOException {
        Map<String, AiProxyDtos.ImageReferenceCandidate> candidateById = new LinkedHashMap<>();
        for (AiProxyDtos.ImageReferenceCandidate image : safeImages(request.images())) {
            candidateById.put(image.id(), image);
        }

        AiProxyDtos.ImageReferenceCandidate baseImage = candidateById.get(plan.baseImageId());
        if (baseImage == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_BASE_IMAGE_MISSING", "Agent 没能定位要编辑的主图");
        }

        String requestedEditMode = request.requestedEditMode() == null ? "" : request.requestedEditMode().trim();
        boolean hasMask = request.maskDataUrl() != null && !request.maskDataUrl().isBlank();
        if ("remover".equals(plan.mode()) && !hasMask) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_MASK_REQUIRED", "请先用画笔涂抹需要删除的物体");
        }
        if ("local-redraw".equals(requestedEditMode) && !hasMask) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_MASK_REQUIRED", "请先用画笔涂抹需要局部重绘的区域");
        }

        List<AiProxyDtos.ImageReferenceCandidate> referenceImages = plan.referenceImageIds().stream()
                .map(candidateById::get)
                .filter(candidate -> candidate != null && !candidate.id().equals(baseImage.id()))
                .toList();
        String requestPrompt = buildImageEditPrompt(request.prompt(), plan, baseImage, referenceImages, hasMask);
        String imageContext = buildImageRoleContext(request.prompt(), baseImage, referenceImages, hasMask, safeImages(request.images()));
        AiProxyService.AgentImageEditJobRequest jobRequest = new AiProxyService.AgentImageEditJobRequest(
                requestPrompt,
                plan.aspectRatio(),
                requireAssetId(baseImage, "主图"),
                referenceImages.stream().map(candidate -> requireAssetId(candidate, "参考图")).toList(),
                request.maskDataUrl(),
                imageContext,
                promptOptimizationModeFor(plan.mode())
        );

        return responseWithJobs(
                plan,
                requestPrompt,
                List.of(toRunJob(aiProxyService.createImageEditJobFromAgent(userId, jobRequest)))
        );
    }

    private AiProxyDtos.AgentRunResponse responseWithoutJobs(AiProxyDtos.AgentPlanResponse plan, String requestPrompt) {
        return new AiProxyDtos.AgentRunResponse(
                plan.allowed(),
                plan.responseMode(),
                plan.rejectionMessage(),
                plan.answerMessage(),
                plan,
                plan.taskType(),
                plan.mode(),
                plan.count(),
                plan.baseImageId(),
                plan.referenceImageIds(),
                plan.aspectRatio(),
                requestPrompt,
                plan.displayTitle(),
                plan.displayMessage(),
                List.of(),
                plan.source()
        );
    }

    private AiProxyDtos.AgentRunResponse responseWithJobs(
            AiProxyDtos.AgentPlanResponse plan,
            String requestPrompt,
            List<AiProxyDtos.AgentRunJob> jobs
    ) {
        return new AiProxyDtos.AgentRunResponse(
                true,
                "execute",
                "",
                "",
                plan,
                plan.taskType(),
                plan.mode(),
                jobs.size(),
                plan.baseImageId(),
                plan.referenceImageIds(),
                plan.aspectRatio(),
                requestPrompt,
                plan.displayTitle(),
                plan.displayMessage(),
                List.copyOf(jobs),
                plan.source()
        );
    }

    private AiProxyDtos.AgentRunJob toRunJob(Map<String, Object> job) {
        return new AiProxyDtos.AgentRunJob(
                String.valueOf(job.getOrDefault("jobId", "")),
                String.valueOf(job.getOrDefault("status", "queued")),
                stringValue(job.get("originalPrompt")),
                stringValue(job.get("optimizedPrompt"))
        );
    }

    private void publishProgress(
            UUID userId,
            String runId,
            String stepId,
            String title,
            String description,
            String stepType,
            String status
    ) {
        if (runId.isBlank()) {
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stepId", stepId);
        event.put("title", title);
        event.put("description", description);
        event.put("stepType", stepType);
        event.put("status", status);
        event.put("createdAt", System.currentTimeMillis());
        eventBroadcaster.publishAgentRunEvent(userId, runId, event);
    }

    private String normalizeClientRunId(String clientRunId) {
        String normalized = clientRunId == null ? "" : clientRunId.trim();
        if (normalized.length() > 80) {
            return normalized.substring(0, 80);
        }
        return normalized;
    }

    private String buildImageEditPrompt(
            String userPrompt,
            AiProxyDtos.AgentPlanResponse plan,
            AiProxyDtos.ImageReferenceCandidate baseImage,
            List<AiProxyDtos.ImageReferenceCandidate> referenceImages,
            boolean hasLocalMask
    ) {
        String readablePrompt = replaceImageReferencesWithRoleNames(userPrompt, baseImage, referenceImages);
        String effectivePrompt = readablePrompt == null || readablePrompt.isBlank()
                ? defaultEditPrompt(plan.mode())
                : readablePrompt;
        List<String> lines = new ArrayList<>();
        lines.add("用户原始指令：" + effectivePrompt);
        lines.add("图片引用说明：");
        lines.add("- 原图：第 1 张 image 文件，画布名称“%s”，这是必须被编辑的目标图片。".formatted(candidateName(baseImage)));
        String originalAspectInstruction = originalAspectRatioInstruction(plan.aspectRatio(), baseImage);
        if (!originalAspectInstruction.isBlank()) {
            lines.add(originalAspectInstruction);
        }
        for (int index = 0; index < referenceImages.size(); index += 1) {
            lines.add("- 参考图 %d：第 %d 张 image 文件，画布名称“%s”，仅作为素材/物体/风格参考；不要把画布名称当成新的编辑指令。".formatted(
                    index + 1,
                    index + 2,
                    candidateName(referenceImages.get(index))
            ));
        }
        if (PLACEMENT_INTENT_PATTERN.matcher(userPrompt == null ? "" : userPrompt).find() && !referenceImages.isEmpty()) {
            lines.add("- 操作类型：放置/合成。把参考图里的指定主体抠取并放到原图指定位置；不要改成“替换人物身上的同类物体”，也不要只做颜色变化。");
            lines.add("- 放置/穿戴到目标位置时需要匹配原图透视、尺度、遮挡、接触阴影、光照方向、反射和景深，让参考物体真实融合在目标人物或场景中。");
        }
        if (hasLocalMask) {
            lines.add("- 局部蒙版：请求同时包含 mask；mask 的透明区域就是用户画圈/涂抹指定的唯一编辑区域。");
            lines.add("- 如果用户说“圈起来的地方”“这里”“这个位置”，必须理解为 mask 透明区域内部；不要把参考图放到画面其他位置。");
        }
        lines.add(switch (plan.mode()) {
            case "background-removal" -> "执行要求：只执行去背景/抠图，识别并保留原图主要主体，把主体以外的一切区域改成纯白背景。";
            case "remover" -> "执行要求：只删除 mask 区域内的内容并自然补全背景，未被 mask 覆盖的区域必须保持原图不变。";
            default -> "执行要求：严格按用户指令编辑原图；如果用户说“把 A 换成某张参考图”，就替换原图中的 A；如果用户说“把参考图里的 A 放在/摆在原图某处”，就执行放置合成，不要替换原图已有物体；保持原图未被指定修改的主体、背景、构图、光影和画幅不变。";
        });
        return String.join("\n", lines);
    }

    private String originalAspectRatioInstruction(String requestedAspectRatio, AiProxyDtos.ImageReferenceCandidate baseImage) {
        if (requestedAspectRatio != null && !requestedAspectRatio.isBlank() && !"auto".equals(requestedAspectRatio.trim())) {
            return "";
        }
        int width = safePositiveDimension(baseImage.width());
        int height = safePositiveDimension(baseImage.height());
        if (width <= 0 || height <= 0) {
            return "- 画幅比例要求：最终输出必须保持原图画幅比例、方向和构图边界，不要添加白边、相框或多余留白来凑比例。";
        }
        int divisor = greatestCommonDivisor(width, height);
        int ratioWidth = width / divisor;
        int ratioHeight = height / divisor;
        return "- 画幅比例要求：最终输出必须保持原图画幅比例（原图宽高 %d:%d，约 %d:%d），保持横竖方向不变，不要添加白边、相框或多余留白来凑比例。"
                .formatted(width, height, ratioWidth, ratioHeight);
    }

    private int safePositiveDimension(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int greatestCommonDivisor(int first, int second) {
        int left = Math.abs(first);
        int right = Math.abs(second);
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return Math.max(1, left);
    }

    private String buildImageRoleContext(
            String userPrompt,
            AiProxyDtos.ImageReferenceCandidate baseImage,
            List<AiProxyDtos.ImageReferenceCandidate> referenceImages,
            boolean hasLocalMask,
            List<AiProxyDtos.ImageReferenceCandidate> allImages
    ) {
        String readablePrompt = replaceImageReferencesWithRoleNames(userPrompt, baseImage, referenceImages, allImages);
        List<String> lines = new ArrayList<>();
        lines.add("图片角色分析（系统根据用户语义推断，优化提示词时必须遵守，但不要原样输出本段）：");
        lines.add("- 用户原始指令中图片引用的语义化结果：" + readablePrompt);
        lines.add("- 原图/编辑目标：“%s”，第 1 张 image 文件，最终要被编辑的是这张图。".formatted(candidateName(baseImage)));
        for (int index = 0; index < referenceImages.size(); index += 1) {
            lines.add("- 参考图 %d：“%s”，第 %d 张 image 文件，只作为素材/物体/风格参考。".formatted(
                    index + 1,
                    candidateName(referenceImages.get(index)),
                    index + 2
            ));
        }
        lines.add("- 优化后的提示词不要输出 @图片ID、内部 ID 或画布短 ID；如需指代图片，请使用“原图”“参考图 1”等角色名称。");
        lines.add(hasLocalMask
                ? "- 局部蒙版：用户涂抹/画圈区域是唯一允许修改的位置；优化时必须保留“只修改该区域，其余不变”。"
                : "- 没有局部蒙版时，也要保留用户指定的位置关系，不要把“放置”误改成“替换”。");
        return String.join("\n", lines);
    }

    private String replaceImageReferencesWithRoleNames(
            String text,
            AiProxyDtos.ImageReferenceCandidate baseImage,
            List<AiProxyDtos.ImageReferenceCandidate> referenceImages
    ) {
        List<AiProxyDtos.ImageReferenceCandidate> allImages = new ArrayList<>();
        allImages.add(baseImage);
        allImages.addAll(referenceImages);
        return replaceImageReferencesWithRoleNames(text, baseImage, referenceImages, allImages);
    }

    private String replaceImageReferencesWithRoleNames(
            String text,
            AiProxyDtos.ImageReferenceCandidate baseImage,
            List<AiProxyDtos.ImageReferenceCandidate> referenceImages,
            List<AiProxyDtos.ImageReferenceCandidate> allImages
    ) {
        String rewritten = text == null ? "" : text;
        List<AiProxyDtos.ImageReferenceCandidate> roleImages = new ArrayList<>();
        roleImages.add(baseImage);
        roleImages.addAll(referenceImages);
        for (AiProxyDtos.ImageReferenceCandidate image : roleImages) {
            rewritten = rewritten.replace("@" + image.id(), imageRoleName(image, baseImage, referenceImages));
        }
        for (AiProxyDtos.ImageReferenceCandidate image : allImages) {
            if (image.index() != null) {
                rewritten = rewritten.replace("图片" + image.index(), imageRoleName(image, baseImage, referenceImages));
                rewritten = rewritten.replace("图" + image.index(), imageRoleName(image, baseImage, referenceImages));
            }
        }
        return rewritten;
    }

    private String imageRoleName(
            AiProxyDtos.ImageReferenceCandidate image,
            AiProxyDtos.ImageReferenceCandidate baseImage,
            List<AiProxyDtos.ImageReferenceCandidate> referenceImages
    ) {
        if (image.id().equals(baseImage.id())) {
            return "原图“%s”".formatted(candidateName(image));
        }
        int referenceIndex = referenceImages.indexOf(image);
        return referenceIndex >= 0
                ? "参考图 %d“%s”".formatted(referenceIndex + 1, candidateName(image))
                : "图片“%s”".formatted(candidateName(image));
    }

    private UUID requireAssetId(AiProxyDtos.ImageReferenceCandidate image, String role) {
        String assetId = image.assetId() == null ? "" : image.assetId().trim();
        if (assetId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_IMAGE_ASSET_REQUIRED", "%s还没有保存图片资源，请稍后重试".formatted(role));
        }
        try {
            return UUID.fromString(assetId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_IMAGE_ASSET_INVALID", "%s图片资源 ID 无效".formatted(role));
        }
    }

    private String promptOptimizationModeFor(String mode) {
        return switch (mode) {
            case "background-removal" -> "background-removal";
            case "remover" -> "image-remover";
            default -> "image-to-image";
        };
    }

    private String defaultEditPrompt(String mode) {
        return switch (mode) {
            case "background-removal" -> "去除图片背景，保留主体，背景改成纯白色";
            case "remover" -> "把圈起来的地方删除掉";
            default -> "按用户要求编辑图片";
        };
    }

    private String candidateName(AiProxyDtos.ImageReferenceCandidate image) {
        return image.name() == null || image.name().isBlank() ? "图片" : image.name().trim();
    }

    private List<AiProxyDtos.ImageReferenceCandidate> safeImages(List<AiProxyDtos.ImageReferenceCandidate> images) {
        return images == null ? List.of() : images;
    }

    private int normalizeCount(int count) {
        return Math.max(1, Math.min(4, count));
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }
}
