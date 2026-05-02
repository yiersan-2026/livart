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
    private static final String DEFAULT_PRODUCT_POSTER_SKILL_ID = "image-craft";
    private static final String PRODUCT_POSTER_TREND_STYLE_TEXT = "流行风格：采用 2025-2026 前沿商品视觉语言——编辑感极简、高级留白、克制奢华、细腻颗粒/纸感纹理、统一低饱和配色和轻微手工质感；整体要现代、时尚、安静、优雅，像高端品牌画册或精品杂志内页。";
    private static final String PRODUCT_POSTER_TREND_LAYOUT_TEXT = "构图规则：整体采用杂志排版 / editorial magazine layout，一张详情图只讲一个重点，产品必须是绝对主角；优先使用杂志式网格、标题区、标签区、留白区和轻微不对称的编辑感构图，层级清晰，大面积留白，元素少而准，道具少而精，强调光影、材质和呼吸感。";
    private static final String PRODUCT_POSTER_TREND_AVOID_TEXT = "避免廉价促销风、红黄爆炸贴、满屏装饰、复杂渐变、拥挤信息流、杂乱贴纸、低端直播间视觉和为了填满画面而堆砌元素。";
    private static final String PRODUCT_POSTER_FACT_GUARD_TEXT = "关于产品本身的厚度、尺寸、规格、容量、材质级别、性能、认证、适配、工艺、数量等事实属性，只能使用用户明确提供的信息，或产品原图/包装上已经清晰可读的现有文字与标识；禁止自行脑补、补全或杜撰任何具体数值、技术参数或宣传结论。未提供就不要写进画面文案，也不要生成不存在的参数标注；如果出现人物、桌面等参照物但没有明确尺寸，只需让产品视觉比例自然可信，不要据此反推出任何未提供参数。";
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
        boolean productPosterRun = "product-poster".equals(plan.mode());
        if (!productPosterRun) {
            publishProgress(userId, runId, "create-image-job", "提交任务", "正在提交图片生成任务。", "generate", "running");
        }
        try {
            AiProxyDtos.AgentRunResponse response = productPosterRun
                    ? runProductPoster(userId, request, plan, externalSkillGuidance, runId)
                    : "text-to-image".equals(plan.taskType())
                            ? runTextToImage(userId, request, plan, externalSkillGuidance)
                            : runImageEdit(userId, request, plan, externalSkillGuidance);
            if (!productPosterRun) {
                publishProgress(userId, runId, "create-image-job", "提交任务", "图片任务已创建，接下来等待生成结果。", "generate", "completed");
            }
            markRunCompleted(userId, runId, runState, response);
            return response;
        } catch (IOException | RuntimeException exception) {
            publishProgress(
                    userId,
                    runId,
                    productPosterRun ? "run-product-posters" : "create-image-job",
                    productPosterRun ? "生成详情图" : "提交任务",
                    productPosterRun ? "商品详情图任务创建失败。" : "图片任务创建失败。",
                    productPosterRun ? "edit" : "generate",
                    "error"
            );
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
        aiProxyService.createTextToImageJobsFromAgent(userId, request.prompt(), plan.aspectRatio(), request.imageResolution(), count, externalSkillGuidance)
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
                promptOptimizationModeFor(plan.mode(), externalSkillGuidance),
                request.imageResolution()
        );

        return responseWithJobs(
                plan,
                requestPrompt,
                List.of(toRunJob(aiProxyService.createImageEditJobFromAgent(userId, jobRequest)))
        );
    }

    private AiProxyDtos.AgentRunResponse runProductPoster(
            UUID userId,
            AiProxyDtos.AgentRunRequest request,
            AiProxyDtos.AgentPlanResponse plan,
            String externalSkillGuidance,
            String runId
    ) throws IOException {
        Map<String, AiProxyDtos.ImageReferenceCandidate> candidateById = new LinkedHashMap<>();
        for (AiProxyDtos.ImageReferenceCandidate image : safeImages(request.images())) {
            candidateById.put(image.id(), image);
        }

        List<AiProxyDtos.ImageReferenceCandidate> productImages = resolveProductPosterImages(request.productPoster(), plan, candidateById);
        AiProxyDtos.ImageReferenceCandidate productImage = productImages.isEmpty() ? null : productImages.get(0);
        if (productImage == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PRODUCT_POSTER_IMAGE_MISSING", "请先选择至少一张产品图片");
        }

        publishProgress(userId, runId, "analyze-product", "分析产品", "已识别产品图片、商品信息和目标使用场景。", "analysis", "completed");
        List<AgentPlannerService.ProductPosterPlanItem> posterPlans = agentPlannerService.createProductPosterPlan(
                userId,
                request.productPoster(),
                productImages,
                plan.aspectRatio(),
                productPosterResearchProgressListener(userId, runId)
        );
        publishProgress(userId, runId, "research-industry", "WebSearch 行业调研", "WebSearch 阶段已结束，正在整理商品详情图方案。", "analysis", "completed");
        int count = Math.min(normalizeProductPosterCount(plan.count()), posterPlans.size());
        if (count <= 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PRODUCT_POSTER_PLAN_EMPTY", "商品详情图规划失败，请补充产品描述后再试");
        }
        publishProgress(userId, runId, "plan-poster-set", "规划详情图", "已规划 " + count + " 张商品详情图的视觉方向、文字描述和卖点表达。", "prompt", "completed");
        publishProgress(userId, runId, "run-product-posters", "生成详情图", "正在并行提交 " + count + " 个商品详情图任务。", "edit", "running");

        String productPosterSkillGuidance = resolveProductPosterSkillGuidance(externalSkillGuidance);
        boolean productSeriesMode = isSeriesProductPoster(request.productPoster());
        UUID productAssetId = requireAssetId(productImage, "产品图");
        List<UUID> referenceAssetIds = new ArrayList<>();
        for (int imageIndex = 1; imageIndex < productImages.size(); imageIndex += 1) {
            referenceAssetIds.add(requireAssetId(productImages.get(imageIndex), "产品参考图"));
        }
        String productInfo = buildProductPosterInfo(request.productPoster());
        List<AiProxyDtos.AgentRunJob> jobs = new ArrayList<>();
        for (int index = 0; index < count; index += 1) {
            AgentPlannerService.ProductPosterPlanItem posterPlan = posterPlans.get(index);
            String requestPrompt = buildProductPosterEditPrompt(
                    request.prompt(),
                    request.productPoster(),
                    productInfo,
                    productImages,
                    posterPlan,
                    plan.aspectRatio(),
                    productSeriesMode,
                    index + 1,
                    count
            );
            String imageContext = appendExternalSkillGuidance(
                    buildProductPosterImageContext(request.productPoster(), productInfo, productImages, posterPlan, plan.aspectRatio(), productSeriesMode),
                    productPosterSkillGuidance
            );
            AiProxyService.AgentImageEditJobRequest jobRequest = new AiProxyService.AgentImageEditJobRequest(
                    requestPrompt,
                    plan.aspectRatio(),
                    productAssetId,
                    referenceAssetIds,
                    "",
                    imageContext,
                    "product-poster",
                    request.imageResolution()
            );
            jobs.add(toRunJob(aiProxyService.createImageEditJobFromAgent(userId, jobRequest)));
        }

        publishProgress(userId, runId, "run-product-posters", "生成详情图", "商品详情图任务已提交，接下来等待生成结果。", "edit", "completed");
        return responseWithJobs(plan, buildProductPosterRunPrompt(request.prompt(), productInfo, count), jobs);
    }

    private ProductIndustryResearchService.ResearchProgressListener productPosterResearchProgressListener(UUID userId, String runId) {
        return new ProductIndustryResearchService.ResearchProgressListener() {
            @Override
            public void onStarted(String query) {
                String description = "正在通过 WebSearch 搜索商品所属行业的流行详情图、竞品视觉、社媒种草和高转化排版参考。";
                if (query != null && !query.isBlank()) {
                    description += " 当前搜索主题：" + safeLength(query, 120);
                }
                publishProgress(userId, runId, "research-industry", "WebSearch 行业调研", description, "analysis", "running");
            }

            @Override
            public void onSources(List<ProductIndustryResearchService.IndustryResearchSource> sources) {
                if (sources == null || sources.isEmpty()) {
                    return;
                }
                publishProgress(
                        userId,
                        runId,
                        "research-industry",
                        "WebSearch 行业调研",
                        "已拿到 WebSearch 来源：" + formatResearchSources(sources),
                        "analysis",
                        "running"
                );
            }

            @Override
            public void onSkipped(String reason) {
                publishProgress(
                        userId,
                        runId,
                        "research-industry",
                        "WebSearch 行业调研",
                        reason == null || reason.isBlank() ? "WebSearch 未获取到可用来源，改用内置行业经验。" : reason,
                        "analysis",
                        "completed"
                );
            }
        };
    }

    private String resolveProductPosterSkillGuidance(String externalSkillGuidance) {
        if (externalSkillGuidance != null && !externalSkillGuidance.isBlank()) {
            return externalSkillGuidance;
        }
        try {
            String guidance = externalSkillService.requirePromptGuidance(DEFAULT_PRODUCT_POSTER_SKILL_ID);
            return guidance == null ? "" : guidance;
        } catch (ApiException exception) {
            return "";
        }
    }

    private List<AiProxyDtos.ImageReferenceCandidate> resolveProductPosterImages(
            AiProxyDtos.ProductPosterRequest productPoster,
            AiProxyDtos.AgentPlanResponse plan,
            Map<String, AiProxyDtos.ImageReferenceCandidate> candidateById
    ) {
        LinkedHashMap<String, AiProxyDtos.ImageReferenceCandidate> selectedImages = new LinkedHashMap<>();
        addProductPosterImage(selectedImages, candidateById, plan.baseImageId());
        List<String> requestedImageIds = productPoster == null ? List.of() : productPoster.normalizedProductImageIds();
        for (String imageId : requestedImageIds) {
            addProductPosterImage(selectedImages, candidateById, imageId);
        }
        for (String imageId : plan.referenceImageIds() == null ? List.<String>of() : plan.referenceImageIds()) {
            addProductPosterImage(selectedImages, candidateById, imageId);
        }
        return List.copyOf(selectedImages.values());
    }

    private void addProductPosterImage(
            LinkedHashMap<String, AiProxyDtos.ImageReferenceCandidate> selectedImages,
            Map<String, AiProxyDtos.ImageReferenceCandidate> candidateById,
            String imageId
    ) {
        if (imageId == null || imageId.isBlank() || selectedImages.containsKey(imageId)) {
            return;
        }
        AiProxyDtos.ImageReferenceCandidate image = candidateById.get(imageId);
        if (image != null) {
            selectedImages.put(imageId, image);
        }
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
            case "view-change" -> "执行要求：执行多角度/改视角编辑。把原图当作完整、固定不动的三维场景处理，根据用户给出的整图视角/观察视角、旋转、倾斜和缩放参数，让相机/观察点围绕整张画面移动并生成新的拍摄视角；相机位置发生变化，场景、人物和物体本身不主动旋转、不重新摆姿势。画面中所有可见元素，包括人物、动物、商品、车辆、家具、道具、背景、地面/室内结构、建筑、光源、阴影和反射，都要按照同一个新相机位姿产生一致透视变化；左侧视角或负方位角要看到人物、车辆、家具、建筑和所有可见物体的左侧面，右侧视角或正方位角要看到所有可见物体的右侧面；人物、动物或角色必须保持原先的身体姿态、头部朝向和眼神方向，不要让角色重新转头、转身或看向新镜头，即使原图角色正对原始镜头，新视角也只是从侧面观察这个固定姿态。" + AiProxyService.VIEW_CHANGE_GAZE_LOCK_TEXT + AiProxyService.VIEW_CHANGE_FRAMING_LOCK_TEXT + "不要只旋转或重绘人物、商品、车辆等单个物体，不要让背景/地面/桌面/车轮/车门/墙面停留在原视角，也不要做左右镜像翻转。尽量保持原图完整内容、各主要元素身份、结构比例、材质、颜色、服装/外观、背景风格、相对位置关系、光影方向、画幅比例和原始景别一致；不要添加白边、相框、说明文字或无关新物体。";
            case "panorama" -> "执行要求：执行完整球形全景转换。把原图当作完整场景的中心视图，输出 2:1 横向 equirectangular panorama 完整球形全景图，也就是 360° 水平视角 + 180° 垂直视角；保持原图场景主题、主体身份、物体种类、相对位置、材质、颜色、光影方向和整体氛围不变。只补全原图视野外为形成完整环绕空间所需的合理延展区域，例如左右环境、天空/天花板、地面/桌面、墙面、道路或室内结构；左右边缘必须自然衔接，顶部和底部空间合理连续，水平线稳定。不要把已有主体重绘成新物体，不要改变原图已有物体的身份、结构比例、颜色、姿态、材质和核心外观，不要添加白边、相框、文字、水印或无关新主体。";
            case "product-poster" -> "执行要求：基于一张或多张产品参考图生成电商商品详情图。必须遵守输入上下文中的产品图模式：单品模式时第 1 张图作为主产品图，其余图作为细节、侧面、包装、材质或 logo 参考；系列模式时所有 image 是同一产品系列的不同产品/SKU/款式/颜色，必须分别保留每个产品的独立外观和差异，不要融合成一个商品。先判断产品所在行业，再选择匹配行业的视觉风格和排版密度，香水/香氛要更有艺术气息、更简洁、更高级留白，数码/贴纸要科技潮流，食品要温暖有食欲，家居要生活方式，美妆要干净通透；可以为产品生成商业背景、使用场景、道具、光影、信息卡片、图标、分割线和详情页模块；如果画面里出现人物、手、桌面、电脑、手机、水杯、家具、包装盒或其他参照物，必须让产品在画面中的视觉比例自然可信，优先依据用户提供的尺寸/规格/容量/适配信息，以及原图/包装上清晰可读的现有文字判断比例；如果未提供具体尺寸，只保持比例自然，不要反推出厚度、容量、尺寸数值或其他未提供参数；" + PRODUCT_POSTER_FACT_GUARD_TEXT + "画面必须加入清晰可读的中文文字描述，包括短标题、2 到 4 个核心卖点、材质/规格/适用场景等短标签；不要把产品变成其他商品，不要生成长段落、乱码、水印、二维码、假品牌或价格。";
            default -> "执行要求：严格按用户指令编辑原图；如果用户说“把 A 换成某张参考图”，就替换原图中的 A；如果用户说“把参考图里的 A 放在/摆在原图某处”，就执行放置合成，不要替换原图已有物体；保持原图未被指定修改的主体、背景、构图、光影和画幅不变。";
        });
        return String.join("\n", lines);
    }

    private String buildProductPosterEditPrompt(
            String userPrompt,
            AiProxyDtos.ProductPosterRequest requestProductPoster,
            String productInfo,
            List<AiProxyDtos.ImageReferenceCandidate> productImages,
            AgentPlannerService.ProductPosterPlanItem posterPlan,
            String aspectRatio,
            boolean seriesMode,
            int index,
            int total
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("用户原始指令：" + (userPrompt == null || userPrompt.isBlank() ? "基于产品图生成商品详情图" : userPrompt.trim()));
        lines.add("产品图说明：");
        lines.addAll(buildProductPosterImageRoleLines(productImages, seriesMode));
        lines.add("产品图模式：" + (seriesMode ? "产品系列（多张 image 是同一系列中的不同产品/SKU，不要融合成一个商品）" : "单个产品（多张 image 是同一产品的角度/细节参考）"));
        lines.add("产品信息：" + productInfo);
        String conversationContext = buildProductPosterConversationContext(requestProductPoster);
        if (!conversationContext.isBlank()) {
            lines.add("商品详情图对话上下文：");
            lines.add(conversationContext);
            lines.add("执行要求：继承这段对话里已经确认过的产品事实、风格偏好、卖点重点、文案语气和排版要求。");
        }
        lines.add("详情图方案 %d/%d：%s。".formatted(index, total, posterPlan.title()));
        if (posterPlan.purpose() != null && !posterPlan.purpose().isBlank()) {
            lines.add("详情图用途：" + posterPlan.purpose());
        }
        lines.add("卡片类型：" + normalizeProductPosterCardType(posterPlan.cardType()));
        if (posterPlan.headline() != null && !posterPlan.headline().isBlank()) {
            lines.add("主标题：" + posterPlan.headline());
        }
        if (posterPlan.subheading() != null && !posterPlan.subheading().isBlank()) {
            lines.add("副标题：" + posterPlan.subheading());
        }
        if (!posterPlan.sellingBullets().isEmpty()) {
            lines.add("卖点短句：" + String.join("；", posterPlan.sellingBullets()));
        }
        if (!posterPlan.tags().isEmpty()) {
            lines.add("标签词：" + String.join("；", posterPlan.tags()));
        }
        if (posterPlan.sceneFocus() != null && !posterPlan.sceneFocus().isBlank()) {
            lines.add("场景焦点：" + posterPlan.sceneFocus());
        }
        if (posterPlan.layoutIntent() != null && !posterPlan.layoutIntent().isBlank()) {
            lines.add("版式规划：" + posterPlan.layoutIntent());
        }
        if (posterPlan.compositionDirectives() != null && !posterPlan.compositionDirectives().isBlank()) {
            lines.add("构图要求：" + posterPlan.compositionDirectives());
        }
        if (posterPlan.copyTone() != null && !posterPlan.copyTone().isBlank()) {
            lines.add("文案语气：" + posterPlan.copyTone());
        }
        if (posterPlan.avoid() != null && !posterPlan.avoid().isBlank()) {
            lines.add("避免事项：" + posterPlan.avoid());
        }
        lines.add("视觉方案：" + posterPlan.prompt());
        lines.add(seriesMode
                ? "执行要求：把所有 image 理解为同一产品系列中的不同产品/SKU/款式/颜色；必须分别保留每个产品的形状、颜色、材质、图案、款式差异和核心识别点。可以按方案做全系列陈列、款式对比、组合场景、套装价值或单品卖点说明；不要把多张产品融合成一个商品，不要只保留其中一个产品。"
                : "执行要求：把这些 image 理解为同一个产品的多张参考图，不要当成多个不同商品；以第 1 张 image 控制产品主体身份和整体外观，其余 image 只补充侧面、背面、细节、包装、材质、logo、纹理和结构参考。");
        lines.add(seriesMode
                ? "保持每个系列产品各自的主体形状、颜色、材质、纹理、logo、图案、边缘、结构比例和核心外观尽量不变；如果同框展示多个产品，要有清晰排布和层级，不要互相遮挡到无法识别。"
                : "保持产品主体形状、颜色、材质、纹理、logo、图案、边缘、结构比例和核心外观尽量不变，不要把产品重绘成别的商品。");
        lines.add("如果画面里出现人物、手、桌面、电脑、手机、水杯、家具、包装盒或其他参照物，必须让产品在画面中的视觉比例自然可信；优先依据产品信息里的尺寸/规格/容量/适配信息，以及原图/包装上清晰可读的现有文字判断比例；如果未提供具体尺寸，只保持比例自然，不要反推出厚度、容量、尺寸数值或其他未提供参数。");
        lines.add(PRODUCT_POSTER_FACT_GUARD_TEXT);
        lines.add("先根据产品信息判断行业，再匹配行业设计风格：香水/香氛/珠宝要艺术、简洁、高级留白、精品画册感，文字更克制；美妆护肤要干净通透、柔和高光；数码/AI/贴纸要科技潮流、桌搭感；食品饮品要温暖有食欲；家居日用要生活方式和空间感；服饰鞋包要穿搭和材质细节。");
        lines.add("每张详情图只聚焦一个主题：首屏吸引力、材质工艺、使用场景、尺寸参数、人群价值或包装送礼其一；不要把所有信息堆在同一张图。");
        lines.add("配色尽量控制在 2 到 3 个主色，优先中性色、低饱和色和符合行业调性的点缀色；整体要安静、高级、有品牌感。");
        lines.add("可以创建符合行业特点的商业背景、使用场景、道具、光影、空间层次、信息卡片、图标和详情页模块；产品必须清晰、可信、可售卖。");
        lines.add("必须在画面中加入可读中文文字描述：短标题、1 到 3 个核心卖点（普通品类可到 4 个）、材质/规格/适用场景等短标签；文字要短句化、模块化、排版整齐，单句尽量不超过 10 个汉字，像品牌画册文案而不是促销海报。");
        lines.add("允许高级留白，但不要空到缺少有效信息层级；不要生成长段落、乱码文字、水印、二维码、无关品牌、虚构价格或过密小字。");
        return String.join("\n", lines);
    }

    private String buildProductPosterImageContext(
            AiProxyDtos.ProductPosterRequest requestProductPoster,
            String productInfo,
            List<AiProxyDtos.ImageReferenceCandidate> productImages,
            AgentPlannerService.ProductPosterPlanItem posterPlan,
            String aspectRatio,
            boolean seriesMode
    ) {
        return """
                商品详情图图片角色分析（系统说明，优化提示词时必须遵守，不要原样输出）：
                %s
                - 产品图模式：%s
                - 产品信息：%s
                - %s
                - %s
                - %s
                - 商品详情图对话上下文：%s
                - 详情图方案：%s
                - 卡片类型：%s
                - 主标题：%s
                - 副标题：%s
                - 卖点短句：%s
                - 标签词：%s
                - 场景焦点：%s
                - 版式规划：%s
                - 构图要求：%s
                - 文案语气：%s
                - 避免事项：%s
                - 详情图用途：%s
                - 目标画幅：%s
                - 优化后的提示词必须围绕“产品主体一致性 + 商品详情页视觉 + 可读文字描述”展开。
                - 如果上面对话上下文里已经确认过产品事实、行业调性、风格偏好、排版要求、禁忌点或文案重点，优化提示词时必须继承。
                - 如果画面里出现人物、手、桌面、电脑、手机、水杯、家具、包装盒或其他参照物，必须让产品在画面中的视觉比例自然可信；优先依据产品信息里的尺寸/规格/容量/适配信息，以及原图/包装上清晰可读的现有文字判断比例；如果未提供具体尺寸，只保持比例自然，不要反推出厚度、容量、尺寸数值或其他未提供参数。
                - %s
                - %s
                - %s
                - 必须先判断产品所在行业，再匹配行业风格：香水/香氛/珠宝使用艺术静物、极简留白、高级光影和克制文字；美妆护肤使用干净通透和柔和高光；数码/AI/贴纸使用科技潮流和桌搭；食品饮品强调食欲与原料；家居日用强调空间生活方式；服饰鞋包强调穿搭和材质细节。
                - 需要补充行业场景、背景、光影、道具、信息卡片、图标、分割线和详情页模块，并加入清晰中文短标题、短卖点、材质/规格/场景标签。
                - 文字要短句化、可读、整齐；不要长段落、乱码、水印、二维码、无关品牌、虚构价格或过密小字。
                """.formatted(
                String.join("\n", buildProductPosterImageRoleLines(productImages, seriesMode)),
                seriesMode ? "产品系列：多张 image 是同一系列的不同产品/SKU/款式/颜色" : "单个产品：多张 image 是同一产品的角度/细节参考",
                productInfo,
                PRODUCT_POSTER_TREND_STYLE_TEXT,
                PRODUCT_POSTER_TREND_LAYOUT_TEXT,
                PRODUCT_POSTER_TREND_AVOID_TEXT,
                buildProductPosterConversationContext(requestProductPoster),
                posterPlan.title(),
                normalizeProductPosterCardType(posterPlan.cardType()),
                safeText(posterPlan.headline()),
                safeText(posterPlan.subheading()),
                joinPosterPlanList(posterPlan.sellingBullets()),
                joinPosterPlanList(posterPlan.tags()),
                safeText(posterPlan.sceneFocus()),
                safeText(posterPlan.layoutIntent()),
                safeText(posterPlan.compositionDirectives()),
                safeText(posterPlan.copyTone()),
                safeText(posterPlan.avoid()),
                posterPlan.purpose() == null ? "" : posterPlan.purpose(),
                aspectRatio == null || aspectRatio.isBlank() ? "3:4" : aspectRatio,
                PRODUCT_POSTER_FACT_GUARD_TEXT,
                seriesMode
                        ? "多张 image 是同一产品系列的不同产品；需要保留每个产品的独立外观和差异，可做系列陈列、对比、组合场景或单品卖点说明。"
                        : "多张 image 都属于同一个产品；第 1 张控制主体身份，其余图片提供侧面、细节、包装、材质和 logo 参考。",
                seriesMode
                        ? "不要把多个产品融合成一个商品，不要只突出其中一张；必须保留每个产品的形状、颜色、材质、图案、款式差异和核心识别点。"
                        : "必须保留产品形状、颜色、材质、纹理、logo、结构比例和核心识别点；不要把产品改成其他款式，也不要生成多个不同商品。"
        );
    }

    private String normalizeProductPosterCardType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank()) {
            return "hero";
        }
        return normalized.replace('_', '-');
    }

    private String joinPosterPlanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("；", values);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> buildProductPosterImageRoleLines(List<AiProxyDtos.ImageReferenceCandidate> productImages, boolean seriesMode) {
        List<AiProxyDtos.ImageReferenceCandidate> images = productImages == null ? List.of() : productImages;
        if (images.isEmpty()) {
            return List.of("- 第 1 张 image 文件是产品原图。");
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < images.size(); index += 1) {
            AiProxyDtos.ImageReferenceCandidate image = images.get(index);
            lines.add("- 第 %d 张 image 文件是%s：“%s”。".formatted(
                    index + 1,
                    seriesMode ? "同一系列中的第 %d 个产品/SKU".formatted(index + 1) : index == 0 ? "主产品图/正面图/主体身份参考" : "同一产品的补充参考图，可能包含侧面、背面、细节、包装、材质或 logo",
                    candidateName(image)
            ));
        }
        return lines;
    }

    private String buildProductPosterRunPrompt(String originalPrompt, String productInfo, int count) {
        String prompt = originalPrompt == null ? "" : originalPrompt.trim();
        return """
                %s
                产品信息：%s
                生成数量：%d 张商品详情图
                """.formatted(prompt.isBlank() ? "基于产品图生成商品详情图" : prompt, productInfo, count).trim();
    }

    private String buildProductPosterInfo(AiProxyDtos.ProductPosterRequest productPoster) {
        if (productPoster == null) {
            return "用户未填写详细产品信息；仅可根据产品图识别可见品类与外观线索，不要补写未提供的厚度、尺寸、容量、适配、认证、工艺或其他参数。";
        }
        List<String> fields = new ArrayList<>();
        appendProductPosterField(fields, "产品图模式", isSeriesProductPoster(productPoster) ? "产品系列，多张图是同一系列不同产品/SKU" : "单个产品，多张图是同一产品角度/细节参考");
        appendProductPosterField(fields, "产品描述", productPoster.productDescription());
        appendProductPosterField(fields, "产品名称", productPoster.productName());
        appendProductPosterField(fields, "产品行业", productPoster.industry());
        appendProductPosterField(fields, "材质", productPoster.material());
        appendProductPosterField(fields, "大小/规格", productPoster.size());
        appendProductPosterField(fields, "颜色", productPoster.color());
        appendProductPosterField(fields, "款式/风格", productPoster.style());
        appendProductPosterField(fields, "适用场景", productPoster.scenarios());
        appendProductPosterField(fields, "使用人群", productPoster.targetAudience());
        appendProductPosterField(fields, "核心卖点", productPoster.sellingPoints());
        appendProductPosterField(fields, "补充参数", productPoster.extraDetails());
        appendProductPosterField(fields, "平台风格", productPoster.platformStyle());
        return fields.isEmpty()
                ? "用户未填写详细产品信息；仅可根据产品图识别可见品类与外观线索，不要补写未提供的厚度、尺寸、容量、适配、认证、工艺或其他参数。"
                : String.join("；", fields);
    }

    private String buildProductPosterConversationContext(AiProxyDtos.ProductPosterRequest productPoster) {
        if (productPoster == null || productPoster.conversationContext() == null) {
            return "";
        }
        String normalized = productPoster.conversationContext()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ');
        List<String> lines = normalized.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(60)
                .toList();
        if (lines.isEmpty()) {
            return "";
        }
        String joined = String.join("\n", lines);
        return joined.length() > 3000 ? joined.substring(0, 3000) : joined;
    }

    private void appendProductPosterField(List<String> fields, String label, String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank()) {
            fields.add(label + "：" + (normalized.length() > 300 ? normalized.substring(0, 300) : normalized));
        }
    }

    private boolean isSeriesProductPoster(AiProxyDtos.ProductPosterRequest productPoster) {
        return productPoster != null && "series".equals(productPoster.normalizedProductMode());
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
            case "panorama" -> "panorama";
            case "product-poster" -> "product-poster";
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
            case "panorama" -> "把原图转换成 2:1 的完整球形全景图";
            case "product-poster" -> "基于产品图生成商品详情图";
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

    private int normalizeProductPosterCount(int count) {
        return Math.max(1, Math.min(6, count));
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private String formatResearchSources(List<ProductIndustryResearchService.IndustryResearchSource> sources) {
        return (sources == null ? List.<ProductIndustryResearchService.IndustryResearchSource>of() : sources).stream()
                .limit(3)
                .map(source -> {
                    String title = source.title() == null || source.title().isBlank() ? "来源" : source.title().trim();
                    return title + " " + source.url();
                })
                .reduce((left, right) -> left + "；" + right)
                .orElse("未返回可展示网址");
    }

    private String safeLength(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
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
