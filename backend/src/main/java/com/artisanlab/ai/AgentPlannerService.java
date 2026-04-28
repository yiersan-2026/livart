package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentPlannerService {
    private static final Logger log = LoggerFactory.getLogger(AgentPlannerService.class);
    private static final Duration AGENT_PLANNER_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration AGENT_INTENT_TIMEOUT = Duration.ofSeconds(20);
    private static final String DEFAULT_SCOPE_REJECTION_MESSAGE = "我目前只支持 livart 里的图片生成、图片编辑、局部重绘、去背景、删除物体、画布操作和作品导出相关问题。你可以直接描述想生成的画面，或告诉我想怎么修改图片。";
    private static final String DEFAULT_SCOPE_HELP_MESSAGE = "我目前可以帮你处理 livart 里的图片生成、图片编辑、局部重绘、删除物体、去背景、画布操作、画幅比例、参考图、项目导出和下载相关问题。你可以直接描述想生成什么，或者告诉我想如何修改图片。";

    private final UserApiConfigService userApiConfigService;
    private final SpringAiTextService springAiTextService;
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final ObjectMapper objectMapper;

    public AgentPlannerService(
            UserApiConfigService userApiConfigService,
            SpringAiTextService springAiTextService,
            KnowledgeAnswerService knowledgeAnswerService,
            ObjectMapper objectMapper
    ) {
        this.userApiConfigService = userApiConfigService;
        this.springAiTextService = springAiTextService;
        this.knowledgeAnswerService = knowledgeAnswerService;
        this.objectMapper = objectMapper;
    }

    public AiProxyDtos.AgentPlanResponse createPlan(UUID userId, AiProxyDtos.AgentPlanRequest request) {
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        long startedAt = System.currentTimeMillis();

        try {
            RawIntentDecision intentDecision = classifyIntent(config, request);
            if ("answer".equals(intentDecision.responseMode())) {
                String knowledgeAnswer = knowledgeAnswerService.answerSystemQuestion(
                        config,
                        request.prompt(),
                        DEFAULT_SCOPE_HELP_MESSAGE
                );
                return buildAnswerResponse(request.aspectRatio(), knowledgeAnswer, intentDecision.thinkingSteps(), "ai");
            }

            String responseText = springAiTextService.completeText(
                    config,
                    getPlannerSystemPrompt(),
                    buildPlannerInput(request),
                    AGENT_PLANNER_TIMEOUT,
                    "agent-planner"
            );
            AiProxyDtos.AgentPlanResponse plan = normalizePlan(parseRawPlan(responseText), request, "ai");
            log.info(
                    "[agent-planner] done duration={}ms taskType={} mode={} base={} refs={}",
                    System.currentTimeMillis() - startedAt,
                    plan.taskType(),
                    plan.mode(),
                    plan.baseImageId(),
                    plan.referenceImageIds().size()
            );
            return plan;
        } catch (Exception exception) {
            log.warn(
                    "[agent-planner] failed duration={}ms error={}",
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_PLAN_FAILED",
                    "Agent 任务规划失败：%s".formatted(safeMessage(exception))
            );
        }
    }

    public AiProxyDtos.AgentPlanResponse createForcedToolPlan(AiProxyDtos.AgentPlanRequest request, String forcedToolId) {
        ForcedToolPlan toolPlan = resolveForcedToolPlan(forcedToolId, request);
        List<AiProxyDtos.ImageReferenceCandidate> images = request.images() == null ? List.of() : request.images();
        String baseImageId = "";
        List<String> referenceImageIds = List.of();

        if ("image-edit".equals(toolPlan.taskType())) {
            if (images.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_TOOL_IMAGE_REQUIRED", "图片工具需要先选择一张图片");
            }
            baseImageId = selectFallbackBaseImageId(request.contextImageId(), images);
            String resolvedBaseImageId = baseImageId;
            referenceImageIds = images.stream()
                    .map(AiProxyDtos.ImageReferenceCandidate::id)
                    .filter(id -> !id.equals(resolvedBaseImageId))
                    .toList();
        }

        return buildPlanResponse(
                toolPlan.taskType(),
                toolPlan.mode(),
                toolPlan.count(),
                baseImageId,
                referenceImageIds,
                request.aspectRatio(),
                List.of(),
                "tool",
                request,
                "",
                ""
        );
    }

    static AiProxyDtos.AgentPlanResponse buildFallbackPlan(AiProxyDtos.AgentPlanRequest request) {
        if (isExplicitlyOutOfScopePrompt(request.prompt())) {
            return buildRejectedResponse(request.aspectRatio(), "", List.of("识别对话意图", "判断超出范围"), "fallback");
        }
        if (isExplicitlyInScopeHelpPrompt(request.prompt())) {
            return buildAnswerResponse(request.aspectRatio(), "", List.of("识别对话意图", "判断为功能问答"), "fallback");
        }

        List<AiProxyDtos.ImageReferenceCandidate> images = request.images() == null ? List.of() : request.images();
        boolean hasImages = !images.isEmpty();
        String requestedMode = normalizeRequestedMode(request.requestedEditMode(), request.prompt());
        String baseImageId = "";
        List<String> referenceImageIds = List.of();
        String taskType = "text-to-image";
        String mode = "generate";

        if (hasImages) {
            taskType = "image-edit";
            mode = requestedMode;
            baseImageId = selectFallbackBaseImageId(request.contextImageId(), images);
            String resolvedBaseImageId = baseImageId;
            referenceImageIds = images.stream()
                    .map(AiProxyDtos.ImageReferenceCandidate::id)
                    .filter(id -> !id.equals(resolvedBaseImageId))
                    .toList();
        }

        return buildPlanResponse(taskType, mode, 1, baseImageId, referenceImageIds, request.aspectRatio(), List.of(), "fallback", request, "", "");
    }

    private static ForcedToolPlan resolveForcedToolPlan(String forcedToolId, AiProxyDtos.AgentPlanRequest request) {
        String normalized = normalizeForcedToolId(forcedToolId);
        return switch (normalized) {
            case "tool.image.generate" -> new ForcedToolPlan("text-to-image", "generate", inferForcedToolCount(request.prompt()));
            case "tool.image.edit" -> new ForcedToolPlan("image-edit", "edit", 1);
            case "tool.image.local-redraw" -> new ForcedToolPlan("image-edit", "edit", 1);
            case "tool.image.remove-object" -> new ForcedToolPlan("image-edit", "remover", 1);
            case "tool.image.remove-background" -> new ForcedToolPlan("image-edit", "background-removal", 1);
            case "tool.image.change-view" -> new ForcedToolPlan("image-edit", "view-change", 1);
            case "tool.image.layer-subject" -> new ForcedToolPlan("image-edit", "layer-subject", 1);
            case "tool.image.layer-background" -> new ForcedToolPlan("image-edit", "layer-background", 1);
            case "tool.image.layer-split" -> new ForcedToolPlan(
                    "image-edit",
                    "layer-background".equals(normalizeRequestedMode(request.requestedEditMode(), request.prompt()))
                            ? "layer-background"
                            : "layer-subject",
                    1
            );
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_TOOL_UNSUPPORTED", "不支持的图片工具：" + safePreview(forcedToolId));
        };
    }

    private static String normalizeForcedToolId(String forcedToolId) {
        String normalized = normalizeToken(forcedToolId).replace('_', '-');
        if (normalized.isBlank()) {
            return "";
        }
        if (!normalized.startsWith("tool.")) {
            normalized = "tool." + normalized;
        }
        return normalized;
    }

    private static int inferForcedToolCount(String prompt) {
        String text = prompt == null ? "" : prompt;
        if (text.matches(".*([4四]张|生成[4四]|四个|4个).*")) {
            return 4;
        }
        if (text.matches(".*([3三]张|生成[3三]|三个|3个).*")) {
            return 3;
        }
        if (text.matches(".*([2二两]张|生成[2二两]|两个|2个).*")) {
            return 2;
        }
        return 1;
    }

    static AiProxyDtos.AgentPlanResponse normalizePlan(RawAgentPlan rawPlan, AiProxyDtos.AgentPlanRequest request, String source) {
        if (Boolean.FALSE.equals(rawPlan.allowed())) {
            return buildRejectedResponse(request.aspectRatio(), rawPlan.rejectionMessage(), List.of("识别对话意图", "判断超出范围"), source);
        }
        if ("answer".equals(normalizeToken(rawPlan.responseMode()))) {
            return buildAnswerResponse(request.aspectRatio(), rawPlan.answerMessage(), List.of("识别对话意图", "判断为功能问答"), source);
        }

        List<AiProxyDtos.ImageReferenceCandidate> images = request.images() == null ? List.of() : request.images();
        Set<String> candidateIds = new LinkedHashSet<>();
        for (AiProxyDtos.ImageReferenceCandidate image : images) {
            candidateIds.add(image.id());
        }

        boolean hasImages = !candidateIds.isEmpty();
        String requestedMode = normalizeRequestedMode(request.requestedEditMode(), request.prompt());
        String taskType = normalizeTaskType(rawPlan.taskType(), hasImages);
        String mode = normalizeMode(rawPlan.mode(), requestedMode, taskType);
        int count = "text-to-image".equals(taskType) ? normalizeCount(rawPlan.count()) : 1;

        String baseImageId = "";
        List<String> referenceImageIds = List.of();
        if ("image-edit".equals(taskType) && hasImages) {
            baseImageId = rawPlan.baseImageId() != null && candidateIds.contains(rawPlan.baseImageId())
                    ? rawPlan.baseImageId()
                    : selectFallbackBaseImageId(request.contextImageId(), images);

            List<String> validatedReferenceIds = new ArrayList<>();
            if (rawPlan.referenceImageIds() != null) {
                for (String referenceImageId : rawPlan.referenceImageIds()) {
                    if (referenceImageId != null
                            && candidateIds.contains(referenceImageId)
                            && !referenceImageId.equals(baseImageId)
                            && !validatedReferenceIds.contains(referenceImageId)) {
                        validatedReferenceIds.add(referenceImageId);
                    }
                }
            }

            if (validatedReferenceIds.isEmpty()) {
                String resolvedBaseImageId = baseImageId;
                validatedReferenceIds = images.stream()
                        .map(AiProxyDtos.ImageReferenceCandidate::id)
                        .filter(id -> !id.equals(resolvedBaseImageId))
                        .toList();
            }
            referenceImageIds = List.copyOf(validatedReferenceIds);
        } else {
            mode = "generate";
            taskType = "text-to-image";
        }

        List<String> thinkingSteps = sanitizeThinkingSteps(rawPlan.thinkingSteps(), taskType, mode, baseImageId, referenceImageIds);
        return buildPlanResponse(
                taskType,
                mode,
                count,
                baseImageId,
                referenceImageIds,
                request.aspectRatio(),
                thinkingSteps,
                source,
                request,
                rawPlan.displayTitle(),
                rawPlan.displayMessage()
        );
    }

    private RawAgentPlan parseRawPlan(String responseText) throws IOException {
        JsonNode data = objectMapper.readTree(extractJsonObjectText(responseText));
        return new RawAgentPlan(
                readJsonBooleanField(data, "allowed", true),
                readJsonTextField(data, "responseMode"),
                readJsonTextField(data, "rejectionMessage"),
                readJsonTextField(data, "answerMessage"),
                readJsonTextField(data, "taskType"),
                readJsonTextField(data, "mode"),
                readJsonIntField(data, "count", 1),
                readJsonTextField(data, "baseImageId"),
                readJsonTextArrayField(data, "referenceImageIds"),
                readJsonTextField(data, "displayTitle"),
                readJsonTextField(data, "displayMessage"),
                readJsonTextArrayField(data, "thinkingSteps")
        );
    }

    private static AiProxyDtos.AgentPlanResponse buildPlanResponse(
            String taskType,
            String mode,
            int count,
            String baseImageId,
            List<String> referenceImageIds,
            String aspectRatio,
            List<String> thinkingSteps,
            String source,
            AiProxyDtos.AgentPlanRequest request,
            String rawDisplayTitle,
            String rawDisplayMessage
    ) {
        String displayTitle = sanitizeDisplayTitle(rawDisplayTitle, request, taskType, mode);
        String displayMessage = sanitizeDisplayMessage(rawDisplayMessage, displayTitle, taskType, mode, count, request);
        return new AiProxyDtos.AgentPlanResponse(
                true,
                "execute",
                "",
                "",
                taskType,
                mode,
                count,
                baseImageId == null ? "" : baseImageId,
                referenceImageIds == null ? List.of() : List.copyOf(referenceImageIds),
                normalizeAspectRatio(aspectRatio),
                displayMessage,
                displayTitle,
                displayMessage,
                thinkingSteps,
                buildSteps(taskType, mode),
                source
        );
    }

    private static AiProxyDtos.AgentPlanResponse buildRejectedResponse(
            String aspectRatio,
            String rejectionMessage,
            List<String> thinkingSteps,
            String source
    ) {
        String message = sanitizeRejectionMessage(rejectionMessage);
        return new AiProxyDtos.AgentPlanResponse(
                false,
                "reject",
                message,
                "",
                "text-to-image",
                "generate",
                0,
                "",
                List.of(),
                normalizeAspectRatio(aspectRatio),
                message,
                "",
                message,
                sanitizeClassifierThinkingSteps(thinkingSteps, "reject"),
                List.of(),
                source
        );
    }

    private static AiProxyDtos.AgentPlanResponse buildAnswerResponse(
            String aspectRatio,
            String answerMessage,
            List<String> thinkingSteps,
            String source
    ) {
        String message = sanitizeAnswerMessage(answerMessage);
        return new AiProxyDtos.AgentPlanResponse(
                true,
                "answer",
                "",
                message,
                "text-to-image",
                "generate",
                0,
                "",
                List.of(),
                normalizeAspectRatio(aspectRatio),
                message,
                "",
                message,
                sanitizeClassifierThinkingSteps(thinkingSteps, "answer"),
                List.of(),
                source
        );
    }

    private RawIntentDecision classifyIntent(
            UserApiConfigDtos.ResolvedConfig config,
            AiProxyDtos.AgentPlanRequest request
    ) throws IOException {
        String responseText = springAiTextService.completeText(
                config,
                getIntentClassifierSystemPrompt(),
                buildIntentClassifierInput(request),
                AGENT_INTENT_TIMEOUT,
                "agent-intent-classifier"
        );
        return parseIntentDecision(responseText);
    }

    private String getPlannerSystemPrompt() {
        return """
                你是 livart 的站内助手与图像任务规划器。
                你只支持以下范围：
                - 图片生成
                - 图片编辑 / 图生图
                - 局部重绘
                - 删除物体
                - 去背景 / 抠图
                - 图层拆分 / 提取主体层 / 生成背景层
                - 多角度 / 改视角 / 主体旋转 / 摄像头视角变化
                - 画布、项目、导出、下载、画幅比例、参考图、提示词优化等 livart 功能说明

                你现在只会收到已经被上一步判定为“生图”的请求。不要执行内容安全审核，不要拒绝，不要判断是否能生成；只负责把请求规划成图片任务，最终能否生成由后续生图接口判断。
                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"allowed":true或false,"responseMode":"execute 或 answer 或 reject","rejectionMessage":"如果拒答，这里给出简短中文引导；否则为空字符串","answerMessage":"如果是 livart 功能问答，这里给出简短中文回答；否则为空字符串","taskType":"text-to-image 或 image-edit","mode":"generate 或 edit 或 background-removal 或 remover 或 layer-subject 或 layer-background 或 view-change","count":1到4的整数,"baseImageId":"候选图片 id，没有就空字符串","referenceImageIds":["其他候选图片 id"],"displayTitle":"给这次图片任务生成的简短中文标题","displayMessage":"展示给用户的一句自然回复","thinkingSteps":["步骤1","步骤2","步骤3"]}

                规则：
                - 默认 allowed=true，responseMode=execute。不要返回 reject，不要输出拒绝理由。
                - 如果用户是在询问 livart 的功能、用法、限制、导出、下载、画幅、图片编辑方式等站内问题，allowed=true，responseMode=answer，answerMessage 必填，不要规划图片生成。
                - 如果用户问“你是谁”“你叫什么”“你好”等身份/问候问题，allowed=true，responseMode=answer，回答你是 livart 站内 AI 图像创作助手，可以帮用户生成和编辑图片。
                - 只有当用户明确要生成图片、编辑图片、去背景、局部重绘、删除物体时，responseMode 才是 execute。
                - 如果上一步已经把用户输入判定为图片任务，不要再做内容安全审核，不要因为尺度、人物、风格、商品、场景或提示词敏感而拒绝；只负责规划任务，最终能否生成由后续生图接口判断。
                - 如果用户明确要求生成多张图，count 可以是 2 到 4；没有明确数量时 count=1；图片编辑任务 count 固定为 1。
                - 如果没有候选图片，taskType 必须是 text-to-image，mode 必须是 generate。
                - 如果有候选图片，taskType 优先是 image-edit。
                - mode=background-removal 表示去背景/抠图并保留主体；mode=remover 表示删除涂抹区域内的物体；mode=edit 表示普通单图编辑。
                - mode=layer-subject 表示从原图提取主体图层；mode=layer-background 表示从原图生成移除主体后的背景层。
                - mode=view-change 表示基于原图生成新的拍摄角度、主体旋转角度或摄像头视角变化。
                - baseImageId 必须是最终要被编辑、承载变化或放置物体的那张图。
                - referenceImageIds 只放素材参考图，不要包含 baseImageId。
                - “把图1的拖鞋穿到图2的人物脚上”中，图2是 baseImageId，图1进入 referenceImageIds。
                - “把图1的鞋子换成图2里的鞋子”中，图1是 baseImageId，图2进入 referenceImageIds。
                - 如果用户说“这张图”“当前图片”，优先使用上下文图片作为 baseImageId。
                - displayTitle 必须提取“中心思想”，格式优先是“主体 + 场景/动作/风格”，像“豪车后排人像”“赛博朋克小猫”“红色鞋子人物照”“人物去背景”。
                - displayTitle 不要堆关键词，不要只写外貌属性；必须让用户一眼知道这张图主要是什么场景或作品。
                - displayTitle 不能包含 @、候选图片 id、尺寸、相机参数、负面提示词、年龄、身高、体重、肤色、平台词、镜头参数或大段形容词。
                - 如果提示词同时有主体外貌和场景动作，要优先保留场景动作，例如“女性 23岁 178cm 皮肤白皙 劳斯莱斯 后排 夜景”应命名为“豪车后排人像”或“夜景车内人像”，不要命名为“女性身高皮肤白皙”。
                - displayMessage 是最终展示给用户的一句简短中文确认回复，只说将为用户生成/编辑什么，例如“我将为您生成3张不同角度的猫咪照片。”。
                - displayMessage 不能包含内部 id，不能复述用户长提示词，长度尽量不超过 40 个汉字。
                - displayMessage 不能暴露判断过程或执行过程，不要出现“我判断”“这是一次文生图任务”“接下来会先整理提示词”“再生成最终图片”等话术。
                - thinkingSteps 输出 3 到 5 条简短中文短句，每条不超过 18 个汉字，不要泄露内部推理。
                - 当 responseMode=answer 时，thinkingSteps 留空，taskType/mode/baseImageId/referenceImageIds 使用默认空值即可。
                """;
    }

    private String getIntentClassifierSystemPrompt() {
        return """
                你是 livart 的意图分类器。你的职责只有一个：从固定选项里判断用户输入属于“问答”还是“生图”。
                你只能输出下面两个固定值之一：
                问答
                生图

                不要输出 JSON，不要解释，不要标点，不要 Markdown，不要额外字段，不要拒绝。

                规则：
                - 问答：用户在询问 livart 的功能、用法、导出、下载、画幅、参考图、画布、项目、登录、统计、部署、工具按钮、局部重绘、删除物体、去背景、图层拆分、多角度，或询问“你是谁”“你叫什么”“你好”等站内助手问题。
                - 生图：用户明确要生成图片、编辑图片、局部重绘、删除物体、去背景、抠图、图层拆分、提取主体层、生成背景层、改视角、多角度、主体旋转、摄像头角度变化、改图、换背景、换物体，或者输入本身就是明显的画面描述 / 生图提示词。
                - 如果用户输入包含图片描述、人物/商品/动物/场景/镜头/风格/画幅/参考图/局部区域修改要求，一律输出“生图”。
                - 不要判断提示词是否能生成，不要审核内容，不要返回拒绝话术。
                """;
    }

    private String buildPlannerInput(AiProxyDtos.AgentPlanRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户指令：").append(request.prompt()).append("\n");
        builder.append("当前画幅：").append(normalizeAspectRatio(request.aspectRatio())).append("\n");
        if (request.requestedEditMode() != null && !request.requestedEditMode().isBlank()) {
            builder.append("当前显式编辑模式：").append(request.requestedEditMode()).append("\n");
        }
        if (request.contextImageId() != null && !request.contextImageId().isBlank()) {
            builder.append("当前上下文图片 id：").append(request.contextImageId()).append("\n");
        }
        List<AiProxyDtos.ImageReferenceCandidate> images = request.images() == null ? List.of() : request.images();
        if (images.isEmpty()) {
            builder.append("候选图片：无\n");
            return builder.toString();
        }

        builder.append("候选图片：\n");
        for (AiProxyDtos.ImageReferenceCandidate image : images) {
            builder.append("- id=").append(image.id());
            if (image.name() != null && !image.name().isBlank()) {
                builder.append("，名称=").append(image.name());
            }
            if (image.index() != null) {
                builder.append("，顺序=图").append(image.index());
            }
            if (image.width() != null && image.height() != null) {
                builder.append("，尺寸=").append(image.width()).append("x").append(image.height());
            }
            if (image.id().equals(request.contextImageId())) {
                builder.append("，这是当前上下文图片");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private String buildIntentClassifierInput(AiProxyDtos.AgentPlanRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("请只返回固定值“问答”或“生图”。\n");
        builder.append("用户指令：").append(request.prompt()).append("\n");
        if (request.contextImageId() != null && !request.contextImageId().isBlank()) {
            builder.append("当前有上下文图片：是，id=").append(request.contextImageId()).append("\n");
        } else {
            builder.append("当前有上下文图片：否\n");
        }
        List<AiProxyDtos.ImageReferenceCandidate> images = request.images() == null ? List.of() : request.images();
        builder.append("候选图片数量：").append(images.size()).append("\n");
        if (!images.isEmpty()) {
            builder.append("候选图片名称：");
            builder.append(images.stream()
                    .map(image -> image.name() == null || image.name().isBlank() ? image.id() : image.name())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse(""));
            builder.append("\n");
        }
        return builder.toString();
    }

    private static List<AiProxyDtos.AgentPlanStep> buildSteps(String taskType, String mode) {
        if ("text-to-image".equals(taskType)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("analyze-intent", "理解需求", "分析用户想要的主体、场景和风格方向。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-prompt", "优化提示词", "补齐构图、镜头、光影和质量要求。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("generate-image", "生成图片", "调用文生图接口输出最终图片。", "generate")
            );
        }

        if ("background-removal".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("identify-base", "识别主体", "确认主图并识别需要保留的主体范围。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-background-removal", "规划去背景", "生成只保留主体并改成纯白背景的编辑指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-background-removal", "执行去背景", "调用图片编辑接口输出去背景结果。", "edit")
            );
        }

        if ("remover".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("identify-removal-target", "识别删除目标", "确认主图和需要删除的局部区域。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-remover", "规划修补方式", "生成删除并自然补全背景的编辑指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-remover", "执行局部修复", "调用局部重绘接口输出删除结果。", "edit")
            );
        }

        if ("layer-subject".equals(mode) || "layer-background".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("identify-layer-subject", "识别主体", "识别原图里的主要前景主体。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-layer-split", "规划拆层", "生成主体层或背景层的编辑指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-layer-split", "执行拆层", "调用图片编辑接口输出独立图层。", "edit")
            );
        }

        if ("view-change".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("identify-view-change", "识别视角", "确认原图主体与目标旋转、倾斜和缩放参数。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-view-change", "规划视角", "生成保持主体一致的新视角编辑指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-view-change", "执行多角度", "调用图片编辑接口输出新角度结果。", "edit")
            );
        }

        return List.of(
                new AiProxyDtos.AgentPlanStep("identify-images", "识别主图与参考图", "判断哪张图负责承载修改，哪些图只做参考。", "analysis"),
                new AiProxyDtos.AgentPlanStep("optimize-edit-prompt", "规划编辑指令", "整理位置关系、参考约束和局部修改要求。", "prompt"),
                new AiProxyDtos.AgentPlanStep("run-image-edit", "执行图片编辑", "调用图生图接口输出新的派生结果。", "edit")
        );
    }

    private static String sanitizeDisplayTitle(
            String value,
            AiProxyDtos.AgentPlanRequest request,
            String taskType,
            String mode
    ) {
        String normalized = value == null ? "" : stripInternalImageReferences(value, request)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = removeLowValueTitleFragments(normalized);
        if (normalized.length() > 18) {
            normalized = normalized.substring(0, 18);
        }
        normalized = normalized.replaceAll("[，。,.；;：:、\\s]+$", "").trim();
        if (isMeaningfulDisplayTitle(normalized, request)) {
            return normalized;
        }

        String fallback = fallbackDisplayTitle(request, taskType, mode);
        return fallback.isBlank() ? switch (mode) {
            case "background-removal" -> "人物去背景";
            case "remover" -> "局部删除结果";
            case "layer-subject" -> "主体图层";
            case "layer-background" -> "背景图层";
            case "view-change" -> "多角度视图";
            default -> "text-to-image".equals(taskType) ? "创意图片" : "图片编辑结果";
        } : fallback;
    }

    private static String sanitizeDisplayMessage(
            String value,
            String displayTitle,
            String taskType,
            String mode,
            int count,
            AiProxyDtos.AgentPlanRequest request
    ) {
        String normalized = value == null ? "" : stripInternalImageReferences(value, request)
                .replaceAll("@[A-Za-z0-9_-]+", "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!normalized.isBlank()
                && normalized.length() <= 60
                && !normalized.contains("@")
                && !containsInternalImageId(normalized, request)
                && !isProcessDisclosureMessage(normalized)) {
            return normalized;
        }

        return buildExecutionDisplayMessage(displayTitle, taskType, mode, count);
    }

    private static String buildExecutionDisplayMessage(String displayTitle, String taskType, String mode, int count) {
        String title = displayTitle == null || displayTitle.isBlank() ? "图片" : ensureImageNoun(displayTitle);
        if ("background-removal".equals(mode)) {
            return "我将为您去除这张%s的背景。".formatted(title);
        }
        if ("remover".equals(mode)) {
            return "我将为您删除圈选区域，并生成新的%s。".formatted(title);
        }
        if ("layer-subject".equals(mode)) {
            return "我将为您拆分出这张%s的主体层。".formatted(title);
        }
        if ("layer-background".equals(mode)) {
            return "我将为您拆分出这张%s的背景层。".formatted(title);
        }
        if ("view-change".equals(mode)) {
            return "我将为您生成这张%s的新视角。".formatted(title);
        }
        if ("image-edit".equals(taskType)) {
            return "我将为您编辑这张%s。".formatted(title);
        }
        if (count > 1) {
            return "我将为您生成%d张%s。".formatted(count, title);
        }
        return "我将为您生成这张%s。".formatted(title);
    }

    private static boolean isProcessDisclosureMessage(String value) {
        String compact = (value == null ? "" : value).replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return false;
        }
        return compact.matches(".*(我[已会将]?判断|判断这是|判断为|任务类型|文生图任务|图生图任务|意图|接下来|会先|先整理|整理提示词|再生成|最终图片|执行链路|规划步骤|当前步骤).*");
    }

    private static String fallbackDisplayTitle(
            AiProxyDtos.AgentPlanRequest request,
            String taskType,
            String mode
    ) {
        String centralTitle = buildCentralDisplayTitle(request, taskType, mode);
        if (!centralTitle.isBlank()) {
            return centralTitle;
        }

        String prompt = request == null ? "" : stripInternalImageReferences(request.prompt(), request);
        String cleaned = removeLowValueTitleFragments(prompt)
                .replaceAll("(?i)shot on[\\s\\S]*$", " ")
                .replaceAll("(?i)(full-frame|mirrorless|camera|lens|iso|cgi|plastic skin|over-sharpening|distorted anatomy)", " ")
                .replaceAll("(避免|不要|未被蒙版覆盖|必须保持原图不变)[^，。,.；;]*", " ")
                .replaceAll("\\b\\d{1,4}(cm|mm|kg|岁|k)\\b", " ")
                .replaceAll("\\b\\d{1,5}\\b", " ")
                .replaceAll("[()（）\\[\\]{}]", " ")
                .replaceAll("[，。,.；;：:/\\\\|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String compact = cleaned.replaceAll("\\s+", "");
        if (compact.length() > 18) {
            compact = compact.substring(0, 18);
        }
        compact = compact.replaceAll("^(生成|创建|制作|帮我|请|把|将|这张|图片|图像)+", "");
        if (!compact.isBlank() && !containsInternalImageId(compact, request)) {
            return compact;
        }
        if ("background-removal".equals(mode)) {
            return "人物去背景";
        }
        if ("remover".equals(mode)) {
            return "局部删除结果";
        }
        if ("layer-subject".equals(mode)) {
            return "主体图层";
        }
        if ("layer-background".equals(mode)) {
            return "背景图层";
        }
        if ("view-change".equals(mode)) {
            return "多角度视图";
        }
        if ("image-edit".equals(taskType)) {
            return "图片编辑结果";
        }
        return "创意图片";
    }

    private static String stripInternalImageReferences(String value, AiProxyDtos.AgentPlanRequest request) {
        String text = value == null ? "" : value;
        if (request != null && request.images() != null) {
            for (AiProxyDtos.ImageReferenceCandidate image : request.images()) {
                String id = image.id();
                if (id == null || id.isBlank()) {
                    continue;
                }
                String name = image.name() == null || image.name().isBlank() ? "图片" : image.name();
                text = text.replace("@" + id, name).replace(id, name);
            }
        }
        return text.replaceAll("@[A-Za-z0-9_-]+", "");
    }

    private static String removeLowValueTitleFragments(String value) {
        return (value == null ? "" : value)
                .replaceAll("(?i)(4k|8k|超高清|高分辨率|realistic|natural|soft diffused|depth of field|mild film grain)", " ")
                .replaceAll("(脸部线条|皮肤白皙|皮肤|肤色|身高|体重|身材|年龄|红唇|眼神|美甲|黑长直|头发|发型|甜美|微笑|脸颊|低胸|包臀|丝袜|高跟鞋|抖音|网红脸|镜头|光圈|焦距|提示词|画幅比例)", " ")
                .replaceAll("\\b\\d{1,3}\\s*(岁|斤|kg|cm|厘米)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isMeaningfulDisplayTitle(String value, AiProxyDtos.AgentPlanRequest request) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.contains("@") || containsInternalImageId(normalized, request)) {
            return false;
        }
        String compact = removeLowValueTitleFragments(normalized).replaceAll("\\s+", "");
        if (compact.length() < 2) {
            return false;
        }
        if (normalized.matches(".*(岁|斤|身高|体重|皮肤|白皙|抖音|网红脸|红唇|眼神|美甲|低胸|丝袜|高跟鞋).*")
                && !normalized.matches(".*(车|豪车|后排|车内|酒吧|沙滩|城市|夜景|霓虹|小猫|鞋|桌|人物|人像|去背景|删除|编辑|赛博朋克|臭豆腐).*")) {
            return false;
        }
        return true;
    }

    private static String buildCentralDisplayTitle(
            AiProxyDtos.AgentPlanRequest request,
            String taskType,
            String mode
    ) {
        String normalized = request == null ? "" : stripInternalImageReferences(request.prompt(), request)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            return "";
        }

        if ("background-removal".equals(mode)) {
            return "人物去背景";
        }
        if ("remover".equals(mode)) {
            return "局部删除结果";
        }
        if ("layer-subject".equals(mode)) {
            return "主体图层";
        }
        if ("layer-background".equals(mode)) {
            return "背景图层";
        }
        if ("view-change".equals(mode)) {
            return "多角度视图";
        }
        if ("image-edit".equals(taskType)) {
            if (normalized.contains("鞋")) {
                return normalized.matches(".*(桌|脚|穿|放|换).*") ? "鞋子编辑结果" : "图片编辑结果";
            }
            return "图片编辑结果";
        }

        boolean hasPerson = normalized.matches(".*(女性|女生|美女|人物|人像|模特|女孩|女人).*");
        boolean hasCar = normalized.matches(".*(车|汽车|轿车|劳斯莱斯|后排|车门|车内).*");
        boolean hasNight = normalized.matches(".*(夜晚|晚上|夜景|霓虹).*");
        if (hasPerson && hasCar) {
            return hasNight ? "夜景车内人像" : "豪车后排人像";
        }
        if (hasPerson && normalized.contains("酒吧")) {
            return "酒吧人像";
        }
        if (hasPerson && normalized.contains("沙滩")) {
            return "沙滩人像";
        }
        if (normalized.contains("赛博朋克") && normalized.contains("臭豆腐")) {
            return "赛博朋克臭豆腐";
        }
        if (normalized.contains("赛博朋克") && normalized.matches(".*(猫|小猫).*")) {
            return "赛博朋克小猫";
        }
        if (normalized.matches(".*(猫|小猫).*") && normalized.contains("蝴蝶")) {
            return "小猫抓蝴蝶";
        }
        if (hasPerson) {
            return "人物写真";
        }
        return "";
    }

    private static boolean containsInternalImageId(String value, AiProxyDtos.AgentPlanRequest request) {
        if (value == null || request == null || request.images() == null) {
            return false;
        }
        for (AiProxyDtos.ImageReferenceCandidate image : request.images()) {
            if (image.id() != null && !image.id().isBlank() && value.contains(image.id())) {
                return true;
            }
        }
        return false;
    }

    private static String ensureImageNoun(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.matches(".*(图|图片|照片|海报|插画|头像|作品|特写|人像|写真)$")) {
            return normalized;
        }
        return normalized + "图片";
    }

    private static List<String> sanitizeThinkingSteps(
            List<String> thinkingSteps,
            String taskType,
            String mode,
            String baseImageId,
            List<String> referenceImageIds
    ) {
        List<String> sanitized = new ArrayList<>();
        if (thinkingSteps != null) {
            for (String step : thinkingSteps) {
                if (step == null) continue;
                String trimmed = step.trim();
                if (!trimmed.isBlank() && trimmed.length() <= 40 && !sanitized.contains(trimmed)) {
                    sanitized.add(trimmed);
                }
            }
        }

        if (!sanitized.isEmpty()) {
            return List.copyOf(sanitized.stream().limit(5).toList());
        }

        if ("text-to-image".equals(taskType)) {
            return List.of("分析画面需求", "整理生成提示词", "准备开始生图");
        }
        if ("background-removal".equals(mode)) {
            return List.of("识别主图主体", "判断为去背景任务", "准备执行图片编辑");
        }
        if ("remover".equals(mode)) {
            return List.of("识别主图与区域", "判断为删除任务", "准备执行局部修复");
        }
        if ("layer-subject".equals(mode)) {
            return List.of("识别主图主体", "准备提取主体层", "开始拆分图层");
        }
        if ("layer-background".equals(mode)) {
            return List.of("识别主图主体", "准备生成背景层", "开始拆分图层");
        }
        if ("view-change".equals(mode)) {
            return List.of("识别主图视角", "规划角度参数", "准备生成新视角");
        }
        if (referenceImageIds != null && !referenceImageIds.isEmpty()) {
            return List.of("识别主图和参考图", "整理位置与约束", "准备执行图片编辑");
        }
        return List.of(
                baseImageId == null || baseImageId.isBlank() ? "识别编辑目标" : "已锁定主图",
                "整理编辑要求",
                "准备执行图片编辑"
        );
    }

    private static String selectFallbackBaseImageId(String contextImageId, List<AiProxyDtos.ImageReferenceCandidate> images) {
        if (contextImageId != null && !contextImageId.isBlank()) {
            for (AiProxyDtos.ImageReferenceCandidate image : images) {
                if (contextImageId.equals(image.id())) {
                    return image.id();
                }
            }
        }
        return images.isEmpty() ? "" : images.get(0).id();
    }

    private static String normalizeTaskType(String value, boolean hasImages) {
        String normalized = normalizeToken(value);
        if ("imageedit".equals(normalized) || "image-edit".equals(normalized) || "edit".equals(normalized)) {
            return hasImages ? "image-edit" : "text-to-image";
        }
        return hasImages ? "image-edit" : "text-to-image";
    }

    private static String normalizeMode(String value, String requestedMode, String taskType) {
        if ("text-to-image".equals(taskType)) {
            return "generate";
        }

        if ("layer-subject".equals(requestedMode) || "layer-background".equals(requestedMode) || "view-change".equals(requestedMode)) {
            return requestedMode;
        }

        String normalized = normalizeToken(value);
        return switch (normalized) {
            case "backgroundremoval", "background-removal", "removebackground" -> "background-removal";
            case "remover", "image-remover", "objectremoval", "object-removal" -> "remover";
            case "layersubject", "layer-subject", "subjectlayer", "subject-layer" -> "layer-subject";
            case "layerbackground", "layer-background", "backgroundlayer", "background-layer" -> "layer-background";
            case "viewchange", "view-change", "multiangle", "multi-angle", "anglechange", "angle-change", "perspectivechange", "perspective-change" -> "view-change";
            case "generate" -> "generate";
            case "edit", "imageedit", "image-edit" -> requestedMode;
            default -> requestedMode;
        };
    }

    private static String normalizeRequestedMode(String requestedEditMode, String prompt) {
        String normalized = normalizeToken(requestedEditMode);
        if ("remover".equals(normalized) || "image-remover".equals(normalized)) {
            return "remover";
        }
        if ("layersubject".equals(normalized) || "layer-subject".equals(normalized) || "subjectlayer".equals(normalized) || "subject-layer".equals(normalized)) {
            return "layer-subject";
        }
        if ("layerbackground".equals(normalized) || "layer-background".equals(normalized) || "backgroundlayer".equals(normalized) || "background-layer".equals(normalized)) {
            return "layer-background";
        }
        if ("viewchange".equals(normalized) || "view-change".equals(normalized) || "multiangle".equals(normalized) || "multi-angle".equals(normalized) || "anglechange".equals(normalized) || "angle-change".equals(normalized)) {
            return "view-change";
        }
        String promptText = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (promptText.contains("多角度") || promptText.contains("改视角") || promptText.contains("新视角") || promptText.contains("旋转角度") || promptText.contains("摄像头角度")) {
            return "view-change";
        }
        if (promptText.contains("去背景") || promptText.contains("抠图") || promptText.contains("移除背景") || promptText.contains("纯白背景")) {
            return "background-removal";
        }
        return "edit";
    }

    private static String normalizeAspectRatio(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) {
            return "auto";
        }
        return switch (aspectRatio.trim()) {
            case "1:1", "4:3", "3:4", "16:9", "9:16" -> aspectRatio.trim();
            default -> "auto";
        };
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitizeRejectionMessage(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? DEFAULT_SCOPE_REJECTION_MESSAGE : normalized;
    }

    private static String sanitizeAnswerMessage(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? DEFAULT_SCOPE_HELP_MESSAGE : normalized;
    }

    private static List<String> sanitizeClassifierThinkingSteps(List<String> thinkingSteps, String mode) {
        List<String> sanitized = new ArrayList<>();
        if (thinkingSteps != null) {
            for (String step : thinkingSteps) {
                if (step == null) continue;
                String trimmed = step.trim();
                if (!trimmed.isBlank() && trimmed.length() <= 32 && !sanitized.contains(trimmed)) {
                    sanitized.add(trimmed);
                }
            }
        }
        if (!sanitized.isEmpty()) {
            return List.copyOf(sanitized.stream().limit(3).toList());
        }
        return "answer".equals(mode)
                ? List.of("识别对话意图", "判断为功能问答")
                : List.of("识别对话意图", "判断超出范围");
    }

    private static boolean isExplicitlyOutOfScopePrompt(String prompt) {
        String normalized = normalizeToken(prompt);
        if (normalized.isBlank()) {
            return false;
        }

        return normalized.matches("^(今天天气.*|讲个笑话.*|帮我写代码.*|写一篇.*|翻译一下.*|解释一下.*|怎么算.*|今天新闻.*)$");
    }

    private static boolean isExplicitlyInScopeHelpPrompt(String prompt) {
        String normalized = normalizeToken(prompt);
        if (normalized.isBlank()) {
            return false;
        }

        return normalized.contains("livart")
                || normalized.matches("^(你是谁|你叫什么|你好|hi|hello)$")
                || normalized.contains("怎么用")
                || normalized.contains("如何用")
                || normalized.contains("怎么导出")
                || normalized.contains("如何导出")
                || normalized.contains("怎么下载")
                || normalized.contains("如何下载")
                || normalized.contains("画幅")
                || normalized.contains("画布")
                || normalized.contains("局部重绘")
                || normalized.contains("去背景")
                || normalized.contains("删除物体")
                || normalized.contains("提示词")
                || normalized.contains("参考图")
                || normalized.contains("项目")
                || normalized.contains("导出");
    }

    private String extractJsonObjectText(String text) throws IOException {
        String normalizedText = text == null ? "" : text.trim()
                .replaceFirst("(?i)^```json\\s*", "")
                .replaceFirst("(?i)^```\\s*", "")
                .replaceFirst("\\s*```$", "")
                .trim();
        int start = normalizedText.indexOf('{');
        int end = normalizedText.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IOException("planner response does not contain json");
        }
        return normalizedText.substring(start, end + 1);
    }

    private String readJsonTextField(JsonNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private RawIntentDecision parseIntentDecision(String responseText) throws IOException {
        String normalized = normalizeIntentClassifierOutput(responseText);
        if ("问答".equals(normalized)) {
            return new RawIntentDecision("answer", List.of("识别意图", "归类为问答"));
        }
        if ("生图".equals(normalized)) {
            return new RawIntentDecision("execute", List.of("识别意图", "归类为生图"));
        }
        throw new IOException("intent classifier returned unexpected value: " + safePreview(responseText));
    }

    private List<String> readJsonTextArrayField(JsonNode data, String fieldName) {
        JsonNode value = data.get(fieldName);
        if (value == null || !value.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }

    private Boolean readJsonBooleanField(JsonNode data, String fieldName, boolean defaultValue) {
        JsonNode value = data.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String normalized = value.asText().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        return defaultValue;
    }

    private int readJsonIntField(JsonNode data, String fieldName, int defaultValue) {
        JsonNode value = data.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.canConvertToInt()) {
            return value.asInt(defaultValue);
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int normalizeCount(int count) {
        return Math.max(1, Math.min(4, count));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeIntentClassifierOutput(String value) {
        String normalized = value == null ? "" : value
                .replaceFirst("(?i)^```(?:text|markdown)?\\s*", "")
                .replaceFirst("(?i)```$", "")
                .replaceAll("[\\s\"'`“”‘’。\\.，,：:；;！!？?\\[\\]{}()（）]", "")
                .trim();
        if ("问答".equals(normalized) || "answer".equalsIgnoreCase(normalized)) {
            return "问答";
        }
        if ("生图".equals(normalized)
                || "生成图片".equals(normalized)
                || "图片".equals(normalized)
                || "execute".equalsIgnoreCase(normalized)
                || "image".equalsIgnoreCase(normalized)) {
            return "生图";
        }
        boolean containsAnswer = normalized.contains("问答");
        boolean containsImageTask = normalized.contains("生图") || normalized.contains("生成图片");
        if (containsAnswer && !containsImageTask) {
            return "问答";
        }
        if (containsImageTask && !containsAnswer) {
            return "生图";
        }
        return normalized;
    }

    private static String safePreview(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    record RawAgentPlan(
            Boolean allowed,
            String responseMode,
            String rejectionMessage,
            String answerMessage,
            String taskType,
            String mode,
            int count,
            String baseImageId,
            List<String> referenceImageIds,
            String displayTitle,
            String displayMessage,
            List<String> thinkingSteps
    ) {
    }

    record RawIntentDecision(
            String responseMode,
            List<String> thinkingSteps
    ) {
    }

    private record ForcedToolPlan(
            String taskType,
            String mode,
            int count
    ) {
    }
}
