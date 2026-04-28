package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.skill.ExternalSkillService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AgentRunService {
    private static final Duration AGENT_RUN_TTL = Duration.ofMinutes(30);
    private static final Pattern PLACEMENT_INTENT_PATTERN = Pattern.compile(
            "放在|放到|放入|放进|放置|摆在|摆到|置于|添加到|放上|摆放|穿到|穿在|穿上|戴到|戴在|装到|装在|应用到|应用在"
    );

    private final AgentPlannerService agentPlannerService;
    private final AiProxyService aiProxyService;
    private final ImageJobEventBroadcaster eventBroadcaster;
    private final ExternalSkillService externalSkillService;
    private final Map<String, AgentRunState> agentRuns = new ConcurrentHashMap<>();

    public AgentRunService(
            AgentPlannerService agentPlannerService,
            AiProxyService aiProxyService,
            ImageJobEventBroadcaster eventBroadcaster,
            ExternalSkillService externalSkillService
    ) {
        this.agentPlannerService = agentPlannerService;
        this.aiProxyService = aiProxyService;
        this.eventBroadcaster = eventBroadcaster;
        this.externalSkillService = externalSkillService;
    }

    public AiProxyDtos.AgentRunResponse run(UUID userId, AiProxyDtos.AgentRunRequest request) throws IOException {
        cleanupAgentRuns();
        String runId = normalizeClientRunId(request.clientRunId());
        AgentRunState runState = registerRun(userId, runId);
        if (runState != null && "completed".equals(runState.status()) && runState.response() != null) {
            return runState.response();
        }
        if (runState != null) {
            runState.markRunning();
            publishAgentRunStatus(userId, runId, runState);
        }

        String forcedToolId = request.forcedToolId() == null ? "" : request.forcedToolId().trim();
        boolean forcedToolRun = !forcedToolId.isBlank();
        String externalSkillGuidance = externalSkillService.requirePromptGuidance(request.externalSkillId());
        if (forcedToolRun) {
            publishProgress(userId, runId, "prepare-tool", "准备图片工具", "已识别为工具栏固定操作，正在准备图片任务。", "analysis", "running");
        } else {
            publishProgress(userId, runId, "identify-intent", "识别意图", "判断你是在问 livart 功能，还是要生成 / 编辑图片。", "analysis", "running");
        }

        AiProxyDtos.AgentPlanResponse plan;
        try {
            plan = forcedToolRun
                    ? agentPlannerService.createForcedToolPlan(request.toPlanRequest(), forcedToolId)
                    : agentPlannerService.createPlan(userId, request.toPlanRequest());
            if (forcedToolRun) {
                publishProgress(userId, runId, "prepare-tool", "准备图片工具", "图片工具任务已准备完成。", "analysis", "completed");
            } else {
                publishProgress(userId, runId, "identify-intent", "识别意图", "已完成意图识别。", "analysis", "completed");
            }
        } catch (RuntimeException exception) {
            publishProgress(
                    userId,
                    runId,
                    forcedToolRun ? "prepare-tool" : "identify-intent",
                    forcedToolRun ? "准备图片工具" : "识别意图",
                    forcedToolRun ? "图片工具任务准备失败。" : "意图识别失败。",
                    "analysis",
                    "error"
            );
            markRunError(userId, runId, runState, exception);
            throw exception;
        }

        if (!"execute".equals(plan.responseMode()) || !plan.allowed()) {
            if ("answer".equals(plan.responseMode())) {
                publishProgress(userId, runId, "knowledge-answer", "检索知识库", "已根据 livart 系统知识整理回答。", "analysis", "completed");
            } else {
                publishProgress(userId, runId, "scope-check", "检查范围", "当前请求不属于 livart 可处理范围。", "analysis", "completed");
            }
            AiProxyDtos.AgentRunResponse response = responseWithoutJobs(plan, request.prompt());
            markRunCompleted(userId, runId, runState, response);
            return response;
        }

        publishProgress(userId, runId, "plan-task", "规划任务", "已规划公开执行步骤，准备创建图片任务。", "analysis", "completed");
        publishProgress(userId, runId, "create-image-job", "提交任务", "正在提交图片生成任务。", "generate", "running");
        try {
            AiProxyDtos.AgentRunResponse response = "text-to-image".equals(plan.taskType())
                    ? runTextToImage(userId, request, plan, externalSkillGuidance)
                    : runImageEdit(userId, request, plan, externalSkillGuidance);
            publishProgress(userId, runId, "create-image-job", "提交任务", "图片任务已创建，接下来等待生成结果。", "generate", "completed");
            markRunCompleted(userId, runId, runState, response);
            return response;
        } catch (IOException | RuntimeException exception) {
            publishProgress(userId, runId, "create-image-job", "提交任务", "图片任务创建失败。", "generate", "error");
            markRunError(userId, runId, runState, exception);
            throw exception;
        }
    }

    public AiProxyDtos.AgentRunStatusResponse getRunStatus(UUID userId, String runId) {
        cleanupAgentRuns();
        String normalizedRunId = normalizeClientRunId(runId);
        if (normalizedRunId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_RUN_ID", "无效的 Agent 任务 ID");
        }

        AgentRunState runState = agentRuns.get(runKey(userId, normalizedRunId));
        if (runState == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent 任务不存在或已过期");
        }
        return runState.toResponse();
    }

    public Map<String, Object> getRunStatusPayload(UUID userId, String runId) {
        return toStatusPayload(getRunStatus(userId, runId));
    }

    private AgentRunState registerRun(UUID userId, String runId) {
        if (runId.isBlank()) {
            return null;
        }
        return agentRuns.computeIfAbsent(runKey(userId, runId), ignored -> new AgentRunState(runId));
    }

    private void markRunCompleted(
            UUID userId,
            String runId,
            AgentRunState runState,
            AiProxyDtos.AgentRunResponse response
    ) {
        if (runState == null) {
            return;
        }
        runState.markCompleted(response);
        publishAgentRunStatus(userId, runId, runState);
    }

    private void markRunError(UUID userId, String runId, AgentRunState runState, Exception exception) {
        if (runState == null) {
            return;
        }
        runState.markError(safeErrorCode(exception), safeMessage(exception));
        publishAgentRunStatus(userId, runId, runState);
    }

    private void publishAgentRunStatus(UUID userId, String runId, AgentRunState runState) {
        eventBroadcaster.publishAgentRunStatus(userId, runId, toStatusPayload(runState.toResponse()));
    }

    private Map<String, Object> toStatusPayload(AiProxyDtos.AgentRunStatusResponse status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.status());
        payload.put("updatedAt", status.updatedAt());
        if (status.response() != null) {
            payload.put("response", status.response());
        }
        if (status.errorMessage() != null && !status.errorMessage().isBlank()) {
            payload.put("errorMessage", status.errorMessage());
        }
        if (status.errorCode() != null && !status.errorCode().isBlank()) {
            payload.put("errorCode", status.errorCode());
        }
        return payload;
    }

    private String runKey(UUID userId, String runId) {
        return userId + ":" + runId;
    }

    private void cleanupAgentRuns() {
        long expiredBefore = System.currentTimeMillis() - AGENT_RUN_TTL.toMillis();
        agentRuns.entrySet().removeIf(entry -> {
            AgentRunState runState = entry.getValue();
            return runState.updatedAt() < expiredBefore && !"running".equals(runState.status());
        });
    }

    private AiProxyDtos.AgentRunResponse runTextToImage(
            UUID userId,
            AiProxyDtos.AgentRunRequest request,
            AiProxyDtos.AgentPlanResponse plan,
            String externalSkillGuidance
    ) throws IOException {
        List<AiProxyDtos.AgentRunJob> jobs = new ArrayList<>();
        int count = normalizeCount(plan.count());
        aiProxyService.createTextToImageJobsFromAgent(userId, request.prompt(), plan.aspectRatio(), count, externalSkillGuidance)
                .forEach(job -> jobs.add(toRunJob(job)));

        return responseWithJobs(plan, request.prompt(), jobs);
    }

    private AiProxyDtos.AgentRunResponse runImageEdit(
            UUID userId,
            AiProxyDtos.AgentRunRequest request,
            AiProxyDtos.AgentPlanResponse plan,
            String externalSkillGuidance
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
        String imageContext = appendExternalSkillGuidance(
                buildImageRoleContext(request.prompt(), baseImage, referenceImages, hasMask, safeImages(request.images())),
                externalSkillGuidance
        );
        AiProxyService.AgentImageEditJobRequest jobRequest = new AiProxyService.AgentImageEditJobRequest(
                requestPrompt,
                plan.aspectRatio(),
                requireAssetId(baseImage, "主图"),
                referenceImages.stream().map(candidate -> requireAssetId(candidate, "参考图")).toList(),
                request.maskDataUrl(),
                imageContext,
                promptOptimizationModeFor(plan.mode(), externalSkillGuidance)
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
            case "layer-subject" -> "执行要求：执行图层拆分的主体层提取。识别原图主要前景主体，只输出同画幅主体图层；主体以外区域必须是透明 alpha，不要生成新背景；主体身份、结构、比例、边缘、颜色、纹理、光影和原有裁切尽量保持原图一致。";
            case "layer-background" -> "执行要求：执行图层拆分的背景层生成。识别并移除原图主要前景主体及其接触阴影、遮挡残影和边缘碎片，用周围背景的纹理、透视、光影和噪点自然补全；保持原图画幅、视角和背景风格不变，不要生成新主体。";
            case "view-change" -> "执行要求：执行多角度/改视角编辑。把原图当作完整三维场景处理，根据用户给出的画面转向/观察视角、旋转、倾斜和缩放参数生成整张图片的新拍摄视角；前景主体、背景、道具、地面/室内结构、车辆、建筑、光源、阴影和反射都要按照同一个新镜头角度产生一致透视变化；不要只旋转或重绘人物、商品、车辆等单个主体。尽量保持原图完整内容、主体身份、结构比例、材质、颜色、服装/外观、背景风格、相对位置关系、光影方向和画幅比例一致；不要添加白边、相框、说明文字或无关新物体。";
            default -> "执行要求：严格按用户指令编辑原图；如果用户说“把 A 换成某张参考图”，就替换原图中的 A；如果用户说“把参考图里的 A 放在/摆在原图某处”，就执行放置合成，不要替换原图已有物体；保持原图未被指定修改的主体、背景、构图、光影和画幅不变。";
        });
        return String.join("\n", lines);
    }

    private String appendExternalSkillGuidance(String imageContext, String externalSkillGuidance) {
        if (externalSkillGuidance == null || externalSkillGuidance.isBlank()) {
            return imageContext;
        }
        if (imageContext == null || imageContext.isBlank()) {
            return externalSkillGuidance.trim();
        }
        return imageContext + "\n\n" + externalSkillGuidance.trim();
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

    private String promptOptimizationModeFor(String mode, String externalSkillGuidance) {
        String nativeMode = switch (mode) {
            case "background-removal" -> "background-removal";
            case "remover" -> "image-remover";
            case "layer-subject" -> "layer-split-subject";
            case "layer-background" -> "layer-split-background";
            case "view-change" -> "view-change";
            default -> "image-to-image";
        };
        if ("image-to-image".equals(nativeMode) && externalSkillGuidance != null && !externalSkillGuidance.isBlank()) {
            return "skill-image-to-image";
        }
        return nativeMode;
    }

    private String defaultEditPrompt(String mode) {
        return switch (mode) {
            case "background-removal" -> "去除图片背景，保留主体，背景改成纯白色";
            case "remover" -> "把圈起来的地方删除掉";
            case "layer-subject" -> "提取主体图层，主体以外透明";
            case "layer-background" -> "移除主体并自然补全背景层";
            case "view-change" -> "基于原图生成新的拍摄视角";
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

    private String safeErrorCode(Exception exception) {
        return exception instanceof ApiException apiException ? apiException.code() : "AGENT_RUN_FAILED";
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message == null || message.isBlank()
                ? "Agent 任务执行失败"
                : message.replaceAll("\\s+", " ").trim();
    }

    private static final class AgentRunState {
        private final String runId;
        private volatile String status = "running";
        private volatile AiProxyDtos.AgentRunResponse response;
        private volatile String errorMessage = "";
        private volatile String errorCode = "";
        private volatile long updatedAt = System.currentTimeMillis();

        private AgentRunState(String runId) {
            this.runId = runId;
        }

        private String status() {
            return status;
        }

        private AiProxyDtos.AgentRunResponse response() {
            return response;
        }

        private long updatedAt() {
            return updatedAt;
        }

        private void markRunning() {
            status = "running";
            errorMessage = "";
            errorCode = "";
            updatedAt = System.currentTimeMillis();
        }

        private void markCompleted(AiProxyDtos.AgentRunResponse response) {
            this.response = response;
            status = "completed";
            errorMessage = "";
            errorCode = "";
            updatedAt = System.currentTimeMillis();
        }

        private void markError(String code, String message) {
            status = "error";
            errorCode = code == null ? "" : code;
            errorMessage = message == null ? "" : message;
            updatedAt = System.currentTimeMillis();
        }

        private AiProxyDtos.AgentRunStatusResponse toResponse() {
            return new AiProxyDtos.AgentRunStatusResponse(
                    runId,
                    status,
                    response,
                    errorMessage,
                    errorCode,
                    updatedAt
            );
        }
    }
}
