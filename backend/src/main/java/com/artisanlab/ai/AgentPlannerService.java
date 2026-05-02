package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
public class AgentPlannerService {
    private static final Logger log = LoggerFactory.getLogger(AgentPlannerService.class);
    private static final Duration AGENT_PLANNER_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration AGENT_INTENT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PRODUCT_POSTER_ANALYSIS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PRODUCT_POSTER_FOLLOW_UP_TIMEOUT = Duration.ofSeconds(20);
    private static final String DEFAULT_SCOPE_REJECTION_MESSAGE = "我目前只支持 livart 里的图片生成、图片编辑、局部重绘、去背景、删除物体、画布操作和作品导出相关问题。你可以直接描述想生成的画面，或告诉我想怎么修改图片。";
    private static final String DEFAULT_SCOPE_HELP_MESSAGE = "我目前可以帮你处理 livart 里的图片生成、图片编辑、局部重绘、删除物体、去背景、画布操作、画幅比例、参考图、项目导出和下载相关问题。你可以直接描述想生成什么，或者告诉我想如何修改图片。";
    private static final String DEFAULT_PRODUCT_POSTER_PLATFORM_STYLE = "通用商业";
    private static final String PRODUCT_POSTER_TREND_STYLE_TEXT = "近期流行风格默认优先参考：编辑感极简、高级留白、克制奢华、细腻颗粒/纸感纹理、统一低饱和配色和轻微手工质感；整体要现代、时尚、安静、优雅，像高端品牌画册或精品杂志内页。";
    private static final String PRODUCT_POSTER_TREND_LAYOUT_TEXT = "构图规则：整体采用杂志排版 / editorial magazine layout，一张详情图只讲一个重点，产品必须是绝对主角；优先使用杂志式网格、标题区、标签区、留白区和轻微不对称的编辑感构图，层级清晰，大面积留白，元素少而准，道具少而精，强调光影、材质和呼吸感。";
    private static final String PRODUCT_POSTER_TREND_AVOID_TEXT = "避免廉价促销风、红黄爆炸贴、满屏装饰、复杂渐变、拥挤信息流、杂乱贴纸、低端直播间视觉和为了填满画面而堆砌元素。";
    private static final String PRODUCT_POSTER_FACT_GUARD_TEXT = "关于产品本身的厚度、尺寸、规格、容量、材质级别、性能、认证、适配、工艺、数量等事实属性，只能使用用户明确提供的信息，或产品原图/包装上已经清晰可读的现有文字与标识；禁止自行脑补、补全或杜撰任何具体数值、技术参数或宣传结论。未提供就不要写进画面文案，也不要生成不存在的参数标注；如果出现人物、桌面等参照物但没有明确尺寸，只需让产品视觉比例自然可信，不要据此反推出任何未提供参数。";
    private static final String PRODUCT_POSTER_ANALYSIS_FACT_GUARD_TEXT = "关于产品本身的厚度、尺寸、规格、容量、材质级别、性能、认证、适配、工艺、数量等事实属性，只能使用用户描述里明确给出的信息；未提到就留空或写“未明确”，绝不能脑补、补全或杜撰。";
    private static final String PRODUCT_INDUSTRY_PERFUME = "香水/香氛";
    private static final String PRODUCT_INDUSTRY_BEAUTY = "美妆/护肤";
    private static final String PRODUCT_INDUSTRY_APPAREL = "服饰/鞋包";
    private static final String PRODUCT_INDUSTRY_FOOD = "食品/饮品";
    private static final String PRODUCT_INDUSTRY_HOME = "家居/日用";
    private static final String PRODUCT_INDUSTRY_DIGITAL = "3C数码";
    private static final String PRODUCT_INDUSTRY_STICKER = "文创/贴纸";
    private static final String PRODUCT_INDUSTRY_GENERAL = "通用商品";
    private static final List<ProductPosterIndustrySignalRule> PRODUCT_POSTER_INDUSTRY_SIGNAL_RULES = List.of(
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_PERFUME,
                    List.of("香水", "香氛", "香调", "留香", "喷雾", "香气"),
                    List.of("前调", "中调", "后调", "花香", "木质", "柑橘", "东方调", "果香"),
                    List.of("喷洒", "闻香", "留香"),
                    List.of("层次感", "高级香", "香气轮廓")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_BEAUTY,
                    List.of("护肤", "美妆", "彩妆", "精华", "面霜", "口红", "粉底"),
                    List.of("成分", "质地", "肤感", "妆感", "色号", "修护", "保湿"),
                    List.of("上妆", "涂抹", "吸收", "肤质", "持妆"),
                    List.of("服帖", "提亮", "细腻", "光泽")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_APPAREL,
                    List.of("服饰", "穿搭", "内衣", "文胸", "内裤", "bra", "睡衣", "家居服", "鞋包"),
                    List.of("面料", "版型", "剪裁", "罩杯", "肩带", "弹性", "支撑", "贴肤"),
                    List.of("穿着", "上身", "内搭", "外穿", "包裹", "舒适"),
                    List.of("修身", "百搭", "曲线", "都会感", "高级感")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_FOOD,
                    List.of("食品", "饮品", "零食", "咖啡", "茶", "巧克力"),
                    List.of("口味", "风味", "原料", "配料", "净含量"),
                    List.of("食用", "冲泡", "加热", "冷藏", "常温"),
                    List.of("新鲜", "香浓", "酥脆", "食欲")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_HOME,
                    List.of("家居", "日用", "家具", "收纳", "餐具", "床品", "抱枕"),
                    List.of("材质", "耐用", "容量", "尺寸", "防水", "收纳"),
                    List.of("客厅", "卧室", "厨房", "浴室", "桌面", "家用", "空间"),
                    List.of("生活方式", "整洁", "氛围感", "空间感")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_DIGITAL,
                    List.of("数码", "耳机", "键盘", "鼠标", "手机", "平板", "电脑", "相机"),
                    List.of("接口", "参数", "续航", "功率", "芯片", "分辨率", "性能"),
                    List.of("适配", "连接", "充电", "蓝牙", "办公"),
                    List.of("效率", "便携", "降噪", "专业")
            ),
            new ProductPosterIndustrySignalRule(
                    PRODUCT_INDUSTRY_STICKER,
                    List.of("贴纸", "徽章", "周边", "logo", "图标", "手账"),
                    List.of("覆膜", "哑光", "磨砂", "印刷", "粘性", "质感"),
                    List.of("贴附", "粘贴", "工位", "桌搭", "水杯", "行李箱"),
                    List.of("圈层", "科技感", "个性化", "表达感")
            )
    );
    private static final List<ProductPosterSlotDefinition> COMMON_PARENT_PRODUCT_SLOTS = List.of(
            responseFieldSlot("product_name", "产品名称", ProductPosterParentSlotGroup.IDENTITY, ProductPosterSlotRequirement.BLOCKING, AiProxyDtos.ProductPosterAnalysisResponse::productName),
            responseFieldSlot("material_or_composition", "材质 / 成分 / 原料", ProductPosterParentSlotGroup.PHYSICAL, ProductPosterSlotRequirement.BLOCKING, AiProxyDtos.ProductPosterAnalysisResponse::material),
            responseFieldSlot("size_or_spec", "尺寸 / 规格 / 容量", ProductPosterParentSlotGroup.PHYSICAL, ProductPosterSlotRequirement.BLOCKING, AiProxyDtos.ProductPosterAnalysisResponse::size),
            responseFieldSlot("color_or_appearance", "颜色 / 外观", ProductPosterParentSlotGroup.PHYSICAL, ProductPosterSlotRequirement.IMPORTANT, AiProxyDtos.ProductPosterAnalysisResponse::color),
            responseFieldSlot("target_audience", "目标人群", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, AiProxyDtos.ProductPosterAnalysisResponse::targetAudience),
            responseFieldSlot("scenarios", "使用场景", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, AiProxyDtos.ProductPosterAnalysisResponse::scenarios),
            responseFieldSlot("selling_points", "核心卖点", ProductPosterParentSlotGroup.MARKETING, ProductPosterSlotRequirement.BLOCKING, AiProxyDtos.ProductPosterAnalysisResponse::sellingPoints),
            responseFieldSlot("style_tone", "风格调性", ProductPosterParentSlotGroup.MARKETING, ProductPosterSlotRequirement.IMPORTANT, AiProxyDtos.ProductPosterAnalysisResponse::style)
    );
    private static final ProductPosterCategorySchema DEFAULT_PRODUCT_POSTER_SCHEMA = new ProductPosterCategorySchema(
            PRODUCT_INDUSTRY_GENERAL,
            List.of(),
            ""
    );
    private static final List<ProductPosterCategorySchema> PRODUCT_POSTER_CATEGORY_SCHEMAS = List.of(
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_PERFUME,
                    List.of(
                            combinedTextSlot("fragrance_notes", "前调 / 中调 / 后调", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.BLOCKING, text -> containsAll(text, "前调", "中调", "后调")),
                            combinedTextSlot("scent_style", "香型气味风格", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "花香", "木质", "柑橘", "东方调", "果香", "皂感", "清新", "甜", "辛香", "香型"))
                    ),
                    "香水详情图的画面气质会被香调决定。请告诉我这款香水的前调、中调、后调分别是什么？如果有花香、木质、柑橘或东方调这类气味方向，也一起给我。"
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_BEAUTY,
                    List.of(
                            combinedTextSlot("ingredients", "核心成分", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.BLOCKING, text -> containsAny(text, "成分", "玻尿酸", "烟酰胺", "维c", "胜肽", "神经酰胺", "植物萃取")),
                            combinedTextSlot("efficacy", "主打功效", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.BLOCKING, text -> containsAny(text, "功效", "补水", "保湿", "修护", "提亮", "抗老", "防晒", "持妆", "遮瑕")),
                            combinedTextSlot("skin_type", "适用肤质 / 使用感", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "肤质", "干皮", "油皮", "混油", "敏感肌", "妆效", "质地"))
                    ),
                    "美妆详情图要先把功效利益点说准。请告诉我核心成分、主打功效，以及适用肤质或使用感。"
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_APPAREL,
                    List.of(
                            combinedTextSlot("fit_or_cut", "版型 / 穿着描述", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "宽松", "修身", "直筒", "廓形", "版型", "高腰", "低腰", "合身", "oversize", "剪裁"))
                    ),
                    ""
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_FOOD,
                    List.of(
                            combinedTextSlot("flavor", "口味 / 风味", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.BLOCKING, text -> containsAny(text, "口味", "风味", "原味", "草莓", "巧克力", "咖啡味", "抹茶", "奶香")),
                            combinedTextSlot("ingredients", "核心原料", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "原料", "配料", "食材", "成分")),
                            combinedTextSlot("usage_method", "食用方式 / 食用提示", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "即食", "冲泡", "加热", "冷藏", "常温", "烘焙", "佐餐", "早餐", "下午茶"))
                    ),
                    "食品详情图需要先把食欲点说准。请告诉我口味/风味、净含量或规格，以及核心原料。"
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_HOME,
                    List.of(
                            combinedTextSlot("space_scene", "适用空间 / 场景", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.BLOCKING, text -> containsAny(text, "客厅", "卧室", "厨房", "浴室", "书房", "玄关", "办公桌", "家居", "空间")),
                            responseFieldSlot("style", "家居风格", ProductPosterParentSlotGroup.MARKETING, ProductPosterSlotRequirement.IMPORTANT, AiProxyDtos.ProductPosterAnalysisResponse::style)
                    ),
                    ""
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_DIGITAL,
                    List.of(
                            combinedTextSlot("compatibility", "适配型号 / 规格", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.BLOCKING, text -> containsAny(text, "型号", "适配", "兼容", "接口", "尺寸", "版本")),
                            combinedTextSlot("function_spec", "核心功能 / 性能参数", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "续航", "功率", "蓝牙", "降噪", "刷新率", "分辨率", "芯片", "存储", "性能", "功能"))
                    ),
                    "数码详情图要先把适配和功能说清楚。请告诉我适配型号/规格、材质工艺和最重要的功能卖点。"
            ),
            new ProductPosterCategorySchema(
                    PRODUCT_INDUSTRY_STICKER,
                    List.of(
                            combinedTextSlot("fit_surface", "适配对象 / 贴附载体", ProductPosterParentSlotGroup.USAGE, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "笔记本", "电脑", "水杯", "行李箱", "平板", "手机壳", "手账", "工位", "桌搭")),
                            combinedTextSlot("surface_finish", "表面工艺 / 质感", ProductPosterParentSlotGroup.EXTRA, ProductPosterSlotRequirement.IMPORTANT, text -> containsAny(text, "亮面", "哑光", "覆膜", "磨砂", "质感", "印刷"))
                    ),
                    ""
            )
    );

    private final UserApiConfigService userApiConfigService;
    private final SpringAiTextService springAiTextService;
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final ProductIndustryResearchService productIndustryResearchService;
    private final ProductPosterVisionAnalysisService productPosterVisionAnalysisService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentPlannerService(
            UserApiConfigService userApiConfigService,
            SpringAiTextService springAiTextService,
            KnowledgeAnswerService knowledgeAnswerService,
            ProductIndustryResearchService productIndustryResearchService,
            ProductPosterVisionAnalysisService productPosterVisionAnalysisService,
            ObjectMapper objectMapper
    ) {
        this.userApiConfigService = userApiConfigService;
        this.springAiTextService = springAiTextService;
        this.knowledgeAnswerService = knowledgeAnswerService;
        this.productIndustryResearchService = productIndustryResearchService;
        this.productPosterVisionAnalysisService = productPosterVisionAnalysisService;
        this.objectMapper = objectMapper;
    }

    public AgentPlannerService(
            UserApiConfigService userApiConfigService,
            SpringAiTextService springAiTextService,
            KnowledgeAnswerService knowledgeAnswerService,
            ProductIndustryResearchService productIndustryResearchService,
            ObjectMapper objectMapper
    ) {
        this(userApiConfigService, springAiTextService, knowledgeAnswerService, productIndustryResearchService, null, objectMapper);
    }

    public AgentPlannerService(
            UserApiConfigService userApiConfigService,
            SpringAiTextService springAiTextService,
            KnowledgeAnswerService knowledgeAnswerService,
            ObjectMapper objectMapper
    ) {
        this(userApiConfigService, springAiTextService, knowledgeAnswerService, null, null, objectMapper);
    }

    @FunctionalInterface
    private interface ProductPosterSlotChecker {
        boolean isSatisfied(AiProxyDtos.ProductPosterAnalysisResponse response, String combinedText);
    }

    private enum ProductPosterParentSlotGroup {
        IDENTITY("产品身份"),
        PHYSICAL("物理属性"),
        USAGE("使用属性"),
        MARKETING("营销表达"),
        EXTRA("行业补充");

        private final String label;

        ProductPosterParentSlotGroup(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private enum ProductPosterSlotRequirement {
        BLOCKING,
        IMPORTANT,
        OPTIONAL
    }

    private record ProductPosterSlotDefinition(
            String key,
            String label,
            ProductPosterParentSlotGroup parentGroup,
            ProductPosterSlotRequirement requirement,
            ProductPosterSlotChecker checker
    ) {
    }

    private record ProductPosterCategorySchema(
            String industry,
            List<ProductPosterSlotDefinition> childSlots,
            String followUpPrompt
    ) {
    }

    private record ProductPosterReadinessResult(
            ProductPosterCategorySchema schema,
            List<ProductPosterSlotDefinition> blockingMissingSlots,
            List<ProductPosterSlotDefinition> importantMissingSlots
    ) {
    }

    private record ProductPosterIndustrySignalRule(
            String industry,
            List<String> identitySignals,
            List<String> attributeSignals,
            List<String> usageSignals,
            List<String> marketingSignals
    ) {
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
            baseImageId = "product-poster".equals(toolPlan.mode())
                    ? selectProductPosterBaseImageId(request.productPoster(), request.contextImageId(), images)
                    : selectFallbackBaseImageId(request.contextImageId(), images);
            String resolvedBaseImageId = baseImageId;
            referenceImageIds = "product-poster".equals(toolPlan.mode())
                    ? selectProductPosterReferenceImageIds(request.productPoster(), resolvedBaseImageId, images)
                    : images.stream()
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
                "product-poster".equals(toolPlan.mode()) ? normalizeProductPosterAspectRatio(request.aspectRatio()) : request.aspectRatio(),
                List.of(),
                "tool",
                request,
                "product-poster".equals(toolPlan.mode()) ? "商品详情图" : "",
                ""
        );
    }

    public List<ProductPosterPlanItem> createProductPosterPlan(
            UUID userId,
            AiProxyDtos.ProductPosterRequest productPoster,
            List<AiProxyDtos.ImageReferenceCandidate> productImages,
            String aspectRatio
    ) {
        return createProductPosterPlan(userId, productPoster, productImages, aspectRatio, ProductIndustryResearchService.ResearchProgressListener.noop());
    }

    public List<ProductPosterPlanItem> createProductPosterPlan(
            UUID userId,
            AiProxyDtos.ProductPosterRequest productPoster,
            List<AiProxyDtos.ImageReferenceCandidate> productImages,
            String aspectRatio,
            ProductIndustryResearchService.ResearchProgressListener researchProgressListener
    ) {
        int count = normalizeProductPosterCount(productPoster);
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        long startedAt = System.currentTimeMillis();
        String productInfo = buildProductPosterInfo(productPoster);
        String industryResearchContext = productIndustryResearchService == null
                ? ""
                : productIndustryResearchService.research(config, productInfo, researchProgressListener)
                        .map(ProductIndustryResearchService.IndustryResearchResult::context)
                        .orElse("");

        try {
            String responseText = springAiTextService.completeText(
                    config,
                    getProductPosterPlannerSystemPrompt(),
                    buildProductPosterPlannerInput(productPoster, productImages, aspectRatio, count, industryResearchContext),
                    AGENT_PLANNER_TIMEOUT,
                    "product-poster-planner"
            );
            List<ProductPosterPlanItem> items = parseProductPosterPlan(responseText, count, aspectRatio);
            if (!items.isEmpty()) {
                if (items.size() < count) {
                    List<ProductPosterPlanItem> fallbackItems = buildFallbackProductPosterPlan(productPoster, count, aspectRatio);
                    List<ProductPosterPlanItem> mergedItems = new ArrayList<>(items);
                    for (ProductPosterPlanItem fallbackItem : fallbackItems) {
                        if (mergedItems.size() >= count) {
                            break;
                        }
                        mergedItems.add(fallbackItem);
                    }
                    items = List.copyOf(mergedItems);
                }
                log.info("[product-poster-planner] done duration={}ms count={}", System.currentTimeMillis() - startedAt, items.size());
                return List.copyOf(items.subList(0, Math.min(count, items.size())));
            }
        } catch (Exception exception) {
            log.warn("[product-poster-planner] fallback duration={}ms error={}", System.currentTimeMillis() - startedAt, safeMessage(exception));
        }

        return buildFallbackProductPosterPlan(productPoster, count, aspectRatio);
    }

    public AiProxyDtos.ProductPosterAnalysisResponse analyzeProductPoster(
            UUID userId,
            AiProxyDtos.ProductPosterAnalysisRequest request
    ) {
        UserApiConfigDtos.ResolvedConfig config = userApiConfigService.getRequiredConfig(userId);
        long startedAt = System.currentTimeMillis();
        try {
            boolean autonomousPlanning = isAutonomousProductPosterRequested(request.description());
            boolean shouldAutoSearchDetailDesignStyle = !hasExplicitProductPosterDetailDesignStyle(request.description());
            ProductPosterVisionAnalysisService.VisionAnalysisResult visionAnalysis = productPosterVisionAnalysisService == null
                    ? null
                    : productPosterVisionAnalysisService
                    .analyze(userId, config, request.images(), request.description())
                    .orElse(null);
            String industryResearchContext = (autonomousPlanning || shouldAutoSearchDetailDesignStyle) && productIndustryResearchService != null
                    ? productIndustryResearchService
                    .research(config, buildProductPosterResearchSeed(request.description(), visionAnalysis))
                    .map(ProductIndustryResearchService.IndustryResearchResult::context)
                    .orElse("")
                    : "";
            String responseText = springAiTextService.completeText(
                    config,
                    getProductPosterAnalysisSystemPrompt(),
                    buildProductPosterAnalysisInput(request.description(), visionAnalysis, autonomousPlanning, industryResearchContext),
                    PRODUCT_POSTER_ANALYSIS_TIMEOUT,
                    "product-poster-analysis"
            );
            AiProxyDtos.ProductPosterAnalysisResponse response = parseProductPosterAnalysis(
                    config,
                    responseText,
                    request.description(),
                    industryResearchContext,
                    visionAnalysis,
                    autonomousPlanning
            );
            log.info("[product-poster-analysis] done duration={}ms", System.currentTimeMillis() - startedAt);
            return response;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("[product-poster-analysis] failed duration={}ms error={}", System.currentTimeMillis() - startedAt, safeMessage(exception));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "PRODUCT_POSTER_ANALYSIS_FAILED",
                    "产品特征分析失败：%s".formatted(safeMessage(exception))
            );
        }
    }

    private String getProductPosterAnalysisSystemPrompt() {
        return """
                你是一位专业的电商产品设计师和商品详情页视觉策划。请从用户的一段自然语言产品描述中，提取可用于生成商品详情图的结构化信息。
                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"summary":"一句话概括产品定位","productName":"产品名称","industry":"产品所属行业","material":"材质","size":"大小/规格","color":"颜色","style":"款式/风格","detailDesignStyle":"商品详情图设计风格","scenarios":"适用场景","targetAudience":"使用人群","sellingPoints":"核心卖点","extraDetails":"补充参数，例如香水前中后调、数码适配型号、食品口味和净含量等","platformStyle":"淘宝/天猫 或 抖音电商 或 小红书种草 或 朋友圈 或 独立站 或 通用商业"}

                规则：
                - 所有实物产品都优先按父槽位理解：产品身份（名称/行业）、物理属性（材质/规格/颜色/外观）、使用属性（场景/人群）、营销表达（卖点/风格）、行业补充（品类特有参数）。
                - 可以基于描述合理归纳行业方向、消费场景、使用人群和卖点，但不要把贴纸、徽章、周边这类文创商品误判成“家居/日用”。
                - 输入里如果有“产品图直读事实”，这些事实等同于用户已经明确给出，可以正常写入结构化字段。
                - 输入里如果有“产品图候选建议”，这些只是待用户确认的候选判断，不能直接写进结构化字段；除非用户原始描述或产品图直读事实也支持该结论。
                - 如果用户明确表示“我不清楚 / 你来规划 / 你来补充 / 全权交给你 / 你看着办”，说明已授权你进入“设计师代策划模式”：
                  1. 行业、使用人群、使用场景、核心卖点、风格、平台风格 这些软信息，可以基于产品图、品牌气质、候选建议和 websearch 调研主动给出最合理的规划结果。
                  2. 不要再为了这些软信息逐项追问确认。
                  3. 只有当你连产品是什么都判断不出来，或者缺失会直接决定真实性的最小硬事实时，才继续追问。
                - %s
                - sellingPoints 用 3 到 6 个简短卖点，使用中文顿号或逗号分隔。
                - industry 用自然中文，例如“香水/香氛”“美妆/护肤”“3C数码”“家居/日用”“食品/饮品”“服饰/鞋包”“文创/贴纸”。
                - style 表示产品本身的款式 / 外观 / 气质，不是详情图版式。
                - detailDesignStyle 表示“商品详情图应该采用什么设计风格 / 版式方向”。如果用户已经明确指定了详情图设计风格，就严格按用户的表达提取；如果用户没指定，但输入里给了 AI websearch 行业调研，请结合调研结果填写当前更适合这类产品、也更流行的详情图设计风格；不要把产品款式误填到这个字段里。
                - extraDetails 只用来放用户明确提到、且不适合塞进固定字段的重要参数，例如香水的前调/中调/后调、护肤品核心成分、数码适配型号、食品口味和净含量；不要补全未给出的规格。
                - platformStyle 必须从给定选项中选一个，默认“通用商业”。
                - 文案要像专业产品设计师写给客户的设计 brief，短、清楚、自然，避免“已完成分析”“目前还缺少”“字段/槽位”等机械话术。
                """.formatted(PRODUCT_POSTER_ANALYSIS_FACT_GUARD_TEXT);
    }

    private String buildProductPosterAnalysisInput(
            String requestDescription,
            ProductPosterVisionAnalysisService.VisionAnalysisResult visionAnalysis,
            boolean autonomousPlanning,
            String industryResearchContext
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户产品描述：\n").append(requestDescription == null ? "" : requestDescription.trim()).append("\n");
        builder.append("当前协作模式：")
                .append(autonomousPlanning
                        ? "设计师代策划模式（用户已明确授权你主动补全软信息并继续规划，除非缺少最小硬事实，否则不要逐项追问）"
                        : "协作确认模式（继续通过自然对话补齐关键信息）")
                .append("\n");
        if (visionAnalysis != null) {
            if (visionAnalysis.summary() != null && !visionAnalysis.summary().isBlank()) {
                builder.append("产品图整体观察：").append(visionAnalysis.summary().trim()).append("\n");
            }
            String confirmedFacts = formatProductPosterFacts(visionAnalysis.confirmedFacts());
            if (!confirmedFacts.isBlank()) {
                builder.append("产品图直读事实（可视为已确认）：\n").append(confirmedFacts).append("\n");
            }
            String suggestedFacts = formatProductPosterFacts(visionAnalysis.suggestedFacts());
            if (!suggestedFacts.isBlank()) {
                builder.append("产品图候选建议（仅供追问时参考，不能直接当作已确认事实）：\n").append(suggestedFacts).append("\n");
            }
        }
        if (industryResearchContext != null && !industryResearchContext.isBlank()) {
            builder.append("AI websearch 行业调研：\n").append(industryResearchContext.trim()).append("\n");
        }
        return builder.toString().trim();
    }

    private AiProxyDtos.ProductPosterAnalysisResponse parseProductPosterAnalysis(
            UserApiConfigDtos.ResolvedConfig config,
            String responseText,
            String requestDescription,
            String industryResearchContext,
            ProductPosterVisionAnalysisService.VisionAnalysisResult visionAnalysis,
            boolean autonomousPlanning
    ) throws IOException {
        JsonNode data = objectMapper.readTree(extractJsonObjectText(responseText));
        AiProxyDtos.ProductPosterAnalysisResponse response = new AiProxyDtos.ProductPosterAnalysisResponse(
                safeLength(readJsonTextField(data, "summary"), 240),
                safeLength(readJsonTextField(data, "productName"), 120),
                safeLength(readJsonTextField(data, "industry"), 120),
                safeLength(readJsonTextField(data, "material"), 200),
                safeLength(readJsonTextField(data, "size"), 120),
                safeLength(readJsonTextField(data, "color"), 120),
                safeLength(readJsonTextField(data, "style"), 200),
                safeLength(readJsonTextField(data, "detailDesignStyle"), 240),
                safeLength(readJsonTextField(data, "scenarios"), 400),
                safeLength(readJsonTextField(data, "targetAudience"), 400),
                safeLength(readJsonTextField(data, "sellingPoints"), 800),
                safeLength(readJsonTextField(data, "extraDetails"), 1000),
                normalizeProductPosterPlatformStyle(readJsonTextField(data, "platformStyle")),
                visionAnalysis == null ? List.of() : visionAnalysis.confirmedFacts(),
                visionAnalysis == null ? List.of() : visionAnalysis.suggestedFacts(),
                List.of(),
                "",
                false,
                ""
        );
        return enrichProductPosterAnalysis(
                config,
                mergeConfirmedFacts(response, requestDescription, visionAnalysis),
                requestDescription,
                industryResearchContext,
                autonomousPlanning
        );
    }

    private AiProxyDtos.ProductPosterAnalysisResponse enrichProductPosterAnalysis(
            UserApiConfigDtos.ResolvedConfig config,
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription,
            String industryResearchContext,
            boolean autonomousPlanning
    ) {
        String industry = normalizeProductPosterIndustry(
                response.industry(),
                requestDescription,
                response.productName(),
                response.summary(),
                response.extraDetails()
        );
        String detailDesignStyle = firstNonBlank(
                response.detailDesignStyle(),
                inferDetailDesignStyleFromResearch(industryResearchContext)
        );
        boolean detailDesignStyleAutoFilled = !hasStructuredValue(response.detailDesignStyle()) && hasStructuredValue(detailDesignStyle);
        String schemaIndustry = resolveProductPosterSchemaIndustry(industry, response, requestDescription);
        ProductPosterReadinessResult readiness = evaluateProductPosterReadiness(schemaIndustry, response, requestDescription, autonomousPlanning);
        List<String> missingInformation = readiness.blockingMissingSlots().stream()
                .map(ProductPosterSlotDefinition::label)
                .distinct()
                .limit(4)
                .toList();
        boolean readyToGenerate = readiness.blockingMissingSlots().isEmpty();
        String nextQuestion = readyToGenerate
                ? ""
                : generateProductPosterNextQuestion(config, response, requestDescription, industry, readiness, autonomousPlanning);
        String assistantMessage = buildProductPosterAnalysisReply(detailDesignStyle, detailDesignStyleAutoFilled, readyToGenerate, nextQuestion);
        return new AiProxyDtos.ProductPosterAnalysisResponse(
                firstNonBlank(response.summary(), safeLength(requestDescription, 240)),
                response.productName(),
                industry,
                response.material(),
                response.size(),
                response.color(),
                response.style(),
                detailDesignStyle,
                response.scenarios(),
                response.targetAudience(),
                response.sellingPoints(),
                response.extraDetails(),
                normalizeProductPosterPlatformStyle(response.platformStyle()),
                List.copyOf(response.confirmedFacts() == null ? List.of() : response.confirmedFacts()),
                List.copyOf(response.suggestedFacts() == null ? List.of() : response.suggestedFacts()),
                List.copyOf(missingInformation),
                nextQuestion,
                readyToGenerate,
                assistantMessage
        );
    }

    private static String buildProductPosterAnalysisReply(
            String detailDesignStyle,
            boolean detailDesignStyleAutoFilled,
            boolean readyToGenerate,
            String nextQuestion
    ) {
        String styleText = hasStructuredValue(detailDesignStyle)
                ? (detailDesignStyleAutoFilled
                    ? "并通过 WebSearch 补全了详情图设计风格：%s。".formatted(detailDesignStyle.trim())
                    : "并确认了详情图设计风格：%s。".formatted(detailDesignStyle.trim()))
                : "";
        if (readyToGenerate) {
            return "我已经把当前能确定的产品属性整理成表格了，%s现在可以开始生成商品详情图。".formatted(styleText);
        }
        String followUp = firstNonBlank(nextQuestion, "还需要你再补充一点关键信息。");
        return "我已经先把当前能确定的产品属性整理成表格了，%s%s".formatted(styleText, followUp);
    }

    private String generateProductPosterNextQuestion(
            UserApiConfigDtos.ResolvedConfig config,
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription,
            String industry,
            ProductPosterReadinessResult readiness,
            boolean autonomousPlanning
    ) {
        String fallbackQuestion = buildProductPosterNextQuestion(readiness);
        if (config == null) {
            return fallbackQuestion;
        }
        try {
            String aiQuestion = springAiTextService.completeText(
                    config,
                    getProductPosterFollowUpSystemPrompt(),
                    buildProductPosterFollowUpInput(response, requestDescription, industry, readiness, fallbackQuestion, autonomousPlanning),
                    PRODUCT_POSTER_FOLLOW_UP_TIMEOUT,
                    "product-poster-followup"
            );
            String normalized = sanitizeProductPosterFollowUpQuestion(aiQuestion);
            return normalized.isBlank() ? fallbackQuestion : normalized;
        } catch (Exception exception) {
            log.warn("[product-poster-followup] fallback error={}", safeMessage(exception));
            return fallbackQuestion;
        }
    }

    private String getProductPosterFollowUpSystemPrompt() {
        return """
                你是一位专业的电商产品设计师和商品详情页视觉策划，正在和客户一起完善商品详情图需求。
                你的目标不是列字段，而是像真正的设计师一样，只问“下一句最有价值的问题”，帮助客户补齐继续生成详情图所必须的信息。

                你只输出一条中文提问，不要 JSON，不要 Markdown，不要编号，不要解释，不要复述“缺少字段/槽位/信息不完整”等系统化措辞。

                要求：
                - 只问一个自然问题，长度尽量控制在 1 到 2 句。
                - 语气像专业设计师，不机械，不像表单，不像客服脚本。
                - 必须围绕当前最关键的缺失信息来问，不要发散。
                - 可以结合行业语境来问，例如香水问香调，贴纸问规格和套装形式，数码问适配型号，但不要杜撰任何产品事实。
                - 如果输入里明确说“用户已授权你全权规划”，除非真的缺少启动任务所必须的最小前提，否则不要继续索取一串属性；优先告诉用户你会先按专业设计师方案继续规划。
                - 不要使用“字段、槽位、readyToGenerate、missingInformation、blocking”这类内部词。
                - 如果已经有一个后端兜底问题，请在它的基础上润色得更自然、更像设计师，但不要改变问题核心。
                """;
    }

    private String buildProductPosterFollowUpInput(
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription,
            String industry,
            ProductPosterReadinessResult readiness,
            String fallbackQuestion,
            boolean autonomousPlanning
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户原始描述：").append(productPosterText(requestDescription)).append("\n");
        builder.append("用户授权状态：").append(autonomousPlanning ? "用户已明确授权你全权规划，请尽量自己接管设计决策。" : "正常协作，需要继续确认关键信息。").append("\n");
        builder.append("当前识别行业：").append(productPosterText(industry)).append("\n");
        builder.append("产品摘要：").append(productPosterText(response.summary())).append("\n");
        builder.append("产品名称：").append(productPosterText(response.productName())).append("\n");
        builder.append("材质：").append(productPosterText(response.material())).append("\n");
        builder.append("大小/规格：").append(productPosterText(response.size())).append("\n");
        builder.append("颜色：").append(productPosterText(response.color())).append("\n");
        builder.append("风格：").append(productPosterText(response.style())).append("\n");
        builder.append("适用场景：").append(productPosterText(response.scenarios())).append("\n");
        builder.append("使用人群：").append(productPosterText(response.targetAudience())).append("\n");
        builder.append("核心卖点：").append(productPosterText(response.sellingPoints())).append("\n");
        builder.append("补充参数：").append(productPosterText(response.extraDetails())).append("\n");
        builder.append("产品图直读事实：").append(formatProductPosterFacts(response.confirmedFacts())).append("\n");
        builder.append("产品图候选建议（待确认）：").append(formatProductPosterFacts(response.suggestedFacts())).append("\n");
        builder.append("当前缺失的关键项：").append(formatSlotLabels(readiness.blockingMissingSlots())).append("\n");
        builder.append("缺失项分组：").append(formatSlotGroupSummary(readiness.blockingMissingSlots())).append("\n");
        builder.append("后端兜底追问：").append(productPosterText(fallbackQuestion)).append("\n");
        builder.append("如果用户自己也不清楚产品信息，优先让用户确认候选建议；如果缺的是尺寸、容量、规格、型号这类硬参数，建议用户补一张包装背标/参数页图片，或者说明可以先按无参数版详情图生成。");
        builder.append("请只输出你最终要对用户说的一句自然追问。");
        return builder.toString();
    }

    private AiProxyDtos.ProductPosterAnalysisResponse mergeConfirmedFacts(
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription,
            ProductPosterVisionAnalysisService.VisionAnalysisResult visionAnalysis
    ) {
        if (visionAnalysis == null || visionAnalysis.confirmedFacts() == null || visionAnalysis.confirmedFacts().isEmpty()) {
            return response;
        }
        return new AiProxyDtos.ProductPosterAnalysisResponse(
                firstNonBlank(response.summary(), visionAnalysis.summary(), safeLength(requestDescription, 240)),
                firstNonBlank(response.productName(), firstFactValue(visionAnalysis.confirmedFacts(), "productName")),
                firstNonBlank(response.industry(), firstFactValue(visionAnalysis.confirmedFacts(), "industry")),
                firstNonBlank(response.material(), firstFactValue(visionAnalysis.confirmedFacts(), "material")),
                firstNonBlank(response.size(), firstFactValue(visionAnalysis.confirmedFacts(), "size")),
                firstNonBlank(response.color(), firstFactValue(visionAnalysis.confirmedFacts(), "color")),
                firstNonBlank(response.style(), firstFactValue(visionAnalysis.confirmedFacts(), "style")),
                response.detailDesignStyle(),
                firstNonBlank(response.scenarios(), firstFactValue(visionAnalysis.confirmedFacts(), "scenarios")),
                firstNonBlank(response.targetAudience(), firstFactValue(visionAnalysis.confirmedFacts(), "targetAudience")),
                firstNonBlank(response.sellingPoints(), firstFactValue(visionAnalysis.confirmedFacts(), "sellingPoints")),
                firstNonBlank(response.extraDetails(), firstFactValue(visionAnalysis.confirmedFacts(), "extraDetails")),
                firstNonBlank(response.platformStyle(), firstFactValue(visionAnalysis.confirmedFacts(), "platformStyle")),
                List.copyOf(visionAnalysis.confirmedFacts()),
                List.copyOf(visionAnalysis.suggestedFacts() == null ? List.of() : visionAnalysis.suggestedFacts()),
                response.missingInformation(),
                response.nextQuestion(),
                response.readyToGenerate(),
                response.assistantMessage()
        );
    }

    private static String firstFactValue(List<AiProxyDtos.ProductPosterFact> facts, String key) {
        if (facts == null || facts.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        for (AiProxyDtos.ProductPosterFact fact : facts) {
            if (fact != null && key.equals(fact.key()) && fact.value() != null && !fact.value().isBlank()) {
                return fact.value().trim();
            }
        }
        return "";
    }

    private static String formatProductPosterFacts(List<AiProxyDtos.ProductPosterFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return "无";
        }
        List<String> lines = new ArrayList<>();
        for (AiProxyDtos.ProductPosterFact fact : facts) {
            if (fact == null || fact.value() == null || fact.value().isBlank()) {
                continue;
            }
            String label = firstNonBlank(fact.label(), fact.key());
            String suffix = fact.note() == null || fact.note().isBlank()
                    ? ""
                    : "（%s）".formatted(fact.note().trim());
            lines.add("- %s：%s%s".formatted(label, fact.value().trim(), suffix));
        }
        return lines.isEmpty() ? "无" : String.join("\n", lines);
    }

    private static String sanitizeProductPosterFollowUpQuestion(String value) {
        String normalized = value == null ? "" : value
                .replaceFirst("(?i)^```[a-z]*\\s*", "")
                .replaceFirst("\\s*```$", "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return safeLength(normalized, 220);
    }

    private static String normalizeProductPosterIndustry(
            String rawIndustry,
            String requestDescription,
            String productName,
            String summary,
            String extraDetails
    ) {
        String normalizedRawIndustry = rawIndustry == null ? "" : rawIndustry.trim();
        String signalIndustry = inferProductPosterIndustryFromSignals(requestDescription, productName, summary, extraDetails);
        if (normalizedRawIndustry.isBlank()) {
            return signalIndustry;
        }
        String rawSchemaIndustry = mapIndustryAliasToSchema(normalizedRawIndustry);
        if (!PRODUCT_INDUSTRY_GENERAL.equals(signalIndustry)
                && !PRODUCT_INDUSTRY_GENERAL.equals(rawSchemaIndustry)
                && !signalIndustry.equals(rawSchemaIndustry)) {
            return signalIndustry;
        }
        if (PRODUCT_INDUSTRY_GENERAL.equals(rawSchemaIndustry) && !PRODUCT_INDUSTRY_GENERAL.equals(signalIndustry)) {
            return safeLength(normalizedRawIndustry, 120);
        }
        return safeLength(normalizedRawIndustry, 120);
    }

    private static String resolveProductPosterSchemaIndustry(
            String normalizedIndustry,
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription
    ) {
        String signalIndustry = inferProductPosterIndustryFromSignals(
                requestDescription,
                response.summary(),
                response.productName(),
                response.material(),
                response.size(),
                response.color(),
                response.style(),
                response.scenarios(),
                response.targetAudience(),
                response.sellingPoints(),
                response.extraDetails()
        );
        if (!PRODUCT_INDUSTRY_GENERAL.equals(signalIndustry)) {
            return signalIndustry;
        }
        return mapIndustryAliasToSchema(normalizedIndustry);
    }

    private static String inferProductPosterIndustryFromSignals(String... values) {
        String combined = combineProductPosterText(values).toLowerCase(Locale.ROOT);
        if (combined.isBlank()) {
            return PRODUCT_INDUSTRY_GENERAL;
        }
        ProductPosterIndustrySignalRule bestRule = null;
        int bestScore = 0;
        int secondScore = 0;
        for (ProductPosterIndustrySignalRule rule : PRODUCT_POSTER_INDUSTRY_SIGNAL_RULES) {
            int score = scoreIndustryRule(combined, rule);
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestRule = rule;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (bestRule == null || bestScore < 2 || bestScore <= secondScore) {
            return PRODUCT_INDUSTRY_GENERAL;
        }
        return bestRule.industry();
    }

    private static int scoreIndustryRule(String combinedText, ProductPosterIndustrySignalRule rule) {
        return scoreIndustrySignalGroup(combinedText, rule.identitySignals(), 3)
                + scoreIndustrySignalGroup(combinedText, rule.attributeSignals(), 2)
                + scoreIndustrySignalGroup(combinedText, rule.usageSignals(), 2)
                + scoreIndustrySignalGroup(combinedText, rule.marketingSignals(), 1);
    }

    private static int scoreIndustrySignalGroup(String combinedText, List<String> signals, int weight) {
        if (signals == null || signals.isEmpty()) {
            return 0;
        }
        int matches = 0;
        for (String signal : signals) {
            if (signal != null && !signal.isBlank() && combinedText.contains(signal.toLowerCase(Locale.ROOT))) {
                matches += 1;
            }
        }
        return Math.min(matches, 2) * weight;
    }

    private static String mapIndustryAliasToSchema(String industry) {
        if (industry == null || industry.isBlank()) {
            return PRODUCT_INDUSTRY_GENERAL;
        }
        String inferred = inferProductPosterIndustryFromSignals(industry);
        if (!PRODUCT_INDUSTRY_GENERAL.equals(inferred)) {
            return inferred;
        }
        String combined = industry.toLowerCase(Locale.ROOT);
        for (ProductPosterIndustrySignalRule rule : PRODUCT_POSTER_INDUSTRY_SIGNAL_RULES) {
            if (combined.contains(rule.industry().toLowerCase(Locale.ROOT))) {
                return rule.industry();
            }
        }
        return PRODUCT_INDUSTRY_GENERAL;
    }

    private static ProductPosterReadinessResult evaluateProductPosterReadiness(
            String industry,
            AiProxyDtos.ProductPosterAnalysisResponse response,
            String requestDescription,
            boolean autonomousPlanning
    ) {
        String combined = combineProductPosterText(
                requestDescription,
                response.summary(),
                response.productName(),
                response.industry(),
                response.material(),
                response.size(),
                response.color(),
                response.style(),
                response.scenarios(),
                response.targetAudience(),
                response.sellingPoints(),
                response.extraDetails()
        );
        ProductPosterCategorySchema schema = findProductPosterCategorySchema(industry);
        List<ProductPosterSlotDefinition> slots = new ArrayList<>(COMMON_PARENT_PRODUCT_SLOTS);
        slots.addAll(schema.childSlots());

        Map<String, ProductPosterSlotDefinition> blockingMissing = new LinkedHashMap<>();
        Map<String, ProductPosterSlotDefinition> importantMissing = new LinkedHashMap<>();
        for (ProductPosterSlotDefinition slot : slots) {
            if (slot == null || slot.checker() == null || slot.requirement() == ProductPosterSlotRequirement.OPTIONAL) {
                continue;
            }
            if (slot.checker().isSatisfied(response, combined)) {
                continue;
            }
            if (slot.requirement() == ProductPosterSlotRequirement.BLOCKING) {
                blockingMissing.putIfAbsent(slot.key(), slot);
            } else if (slot.requirement() == ProductPosterSlotRequirement.IMPORTANT) {
                importantMissing.putIfAbsent(slot.key(), slot);
            }
        }

        if (autonomousPlanning) {
            relaxBlockingSlotsForAutonomousPlanning(industry, response, schema, blockingMissing);
        }

        return new ProductPosterReadinessResult(
                schema,
                List.copyOf(blockingMissing.values()),
                List.copyOf(importantMissing.values())
        );
    }

    private static String buildProductPosterNextQuestion(ProductPosterReadinessResult readiness) {
        if (readiness.blockingMissingSlots().isEmpty()) {
            return "";
        }
        List<ProductPosterSlotDefinition> childBlockingMissing = readiness.blockingMissingSlots().stream()
                .filter(slot -> isChildSchemaSlot(readiness.schema(), slot))
                .toList();
        List<ProductPosterSlotDefinition> commonBlockingMissing = readiness.blockingMissingSlots().stream()
                .filter(slot -> !isChildSchemaSlot(readiness.schema(), slot))
                .toList();

        String specificQuestion = childBlockingMissing.isEmpty() || readiness.schema().followUpPrompt() == null || readiness.schema().followUpPrompt().isBlank()
                ? ""
                : readiness.schema().followUpPrompt().trim();
        String commonQuestion = commonBlockingMissing.isEmpty()
                ? ""
                : buildDesignerCommonFollowUp(readiness.schema(), commonBlockingMissing);

        if (!specificQuestion.isBlank() && !commonQuestion.isBlank()) {
            return specificQuestion + " 另外，" + commonQuestion;
        }
        if (!specificQuestion.isBlank()) {
            return specificQuestion;
        }
        if (!commonQuestion.isBlank()) {
            return commonQuestion;
        }
        return buildDesignerCommonFollowUp(readiness.schema(), readiness.blockingMissingSlots());
    }

    private static String buildDesignerCommonFollowUp(ProductPosterCategorySchema schema, List<ProductPosterSlotDefinition> slots) {
        if (slots == null || slots.isEmpty()) {
            return "从产品设计角度，我还需要一个关键信息：你最想让买家记住这个产品的哪一点？";
        }
        String industry = schema == null ? PRODUCT_INDUSTRY_GENERAL : schema.industry();
        boolean needsName = hasSlotKey(slots, "product_name");
        boolean needsMaterial = hasSlotKey(slots, "material_or_composition");
        boolean needsSize = hasSlotKey(slots, "size_or_spec");
        boolean needsScenarios = hasSlotKey(slots, "scenarios") || hasSlotKey(slots, "space_scene");
        boolean needsSellingPoints = hasSlotKey(slots, "selling_points");

        if (needsName) {
            return "这款产品准备用什么名称展示？我需要用它来确定详情页主标题和第一眼定位。";
        }
        if (PRODUCT_INDUSTRY_STICKER.equals(industry) && needsSize) {
            return "我会按 AI/科技桌搭贴纸的方向来设计。为了让参数图和购买信息准确，这套贴纸的具体规格是多少？比如单张尺寸、张数、图标款/图文款或套装组合。";
        }
        if (needsSize && needsScenarios) {
            return "为了把详情页的比例、参数标注和场景图做准，请告诉我这个产品的具体尺寸/容量/套装规格，以及最想呈现的使用场景。";
        }
        if (needsSize) {
            return "为了让详情页里的比例、参数标注和画面构图更可信，这个产品的尺寸、容量或套装规格是多少？";
        }
        if (needsMaterial) {
            return "为了把材质质感做得像真实商品页，请告诉我它的主要材质、成分或工艺。";
        }
        if (needsSellingPoints) {
            return "从产品设计角度，我需要先抓住购买理由：你最想强调的 2 到 3 个核心卖点是什么？";
        }
        if (needsScenarios) {
            return "为了把场景图做得更有代入感，这款产品最希望用户在哪些场景里使用？比如办公桌搭、送礼、通勤、户外或家用。";
        }
        return "为了把详情图做得更像专业商品页，你再补充一下这些关键信息就行：" + formatSlotLabels(slots) + "。";
    }

    private static boolean hasSlotKey(List<ProductPosterSlotDefinition> slots, String key) {
        if (slots == null || key == null || key.isBlank()) {
            return false;
        }
        for (ProductPosterSlotDefinition slot : slots) {
            if (slot != null && key.equals(slot.key())) {
                return true;
            }
        }
        return false;
    }

    private static ProductPosterCategorySchema findProductPosterCategorySchema(String industry) {
        if (industry == null || industry.isBlank()) {
            return DEFAULT_PRODUCT_POSTER_SCHEMA;
        }
        for (ProductPosterCategorySchema schema : PRODUCT_POSTER_CATEGORY_SCHEMAS) {
            if (schema.industry().equals(industry)) {
                return schema;
            }
        }
        return DEFAULT_PRODUCT_POSTER_SCHEMA;
    }

    private static boolean isChildSchemaSlot(ProductPosterCategorySchema schema, ProductPosterSlotDefinition target) {
        if (schema == null || target == null) {
            return false;
        }
        for (ProductPosterSlotDefinition slot : schema.childSlots()) {
            if (slot.key().equals(target.key())) {
                return true;
            }
        }
        return false;
    }

    private static String formatSlotLabels(List<ProductPosterSlotDefinition> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        return slots.stream()
                .map(ProductPosterSlotDefinition::label)
                .distinct()
                .limit(4)
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
    }

    private static String formatSlotGroupSummary(List<ProductPosterSlotDefinition> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        Map<ProductPosterParentSlotGroup, List<String>> labelsByGroup = new LinkedHashMap<>();
        for (ProductPosterSlotDefinition slot : slots) {
            labelsByGroup.computeIfAbsent(slot.parentGroup(), ignored -> new ArrayList<>());
            if (!labelsByGroup.get(slot.parentGroup()).contains(slot.label())) {
                labelsByGroup.get(slot.parentGroup()).add(slot.label());
            }
        }
        List<String> groups = new ArrayList<>();
        for (Map.Entry<ProductPosterParentSlotGroup, List<String>> entry : labelsByGroup.entrySet()) {
            groups.add(entry.getKey().label() + "（" + String.join("、", entry.getValue()) + "）");
        }
        return String.join("；", groups);
    }

    private static ProductPosterSlotDefinition responseFieldSlot(
            String key,
            String label,
            ProductPosterParentSlotGroup parentGroup,
            ProductPosterSlotRequirement requirement,
            Function<AiProxyDtos.ProductPosterAnalysisResponse, String> extractor
    ) {
        return new ProductPosterSlotDefinition(
                key,
                label,
                parentGroup,
                requirement,
                (response, combinedText) -> hasStructuredValue(extractor.apply(response))
        );
    }

    private static ProductPosterSlotDefinition combinedTextSlot(
            String key,
            String label,
            ProductPosterParentSlotGroup parentGroup,
            ProductPosterSlotRequirement requirement,
            Predicate<String> predicate
    ) {
        return new ProductPosterSlotDefinition(
                key,
                label,
                parentGroup,
                requirement,
                (response, combinedText) -> predicate.test(combinedText == null ? "" : combinedText)
        );
    }

    private static boolean hasStructuredValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return !normalized.isBlank() && !"未明确".equals(normalized) && !"未知".equals(normalized);
    }

    private static boolean isAutonomousProductPosterRequested(String requestDescription) {
        boolean explicitDelegation = containsAny(
                requestDescription,
                "你来规划",
                "你帮我规划",
                "帮我规划",
                "全权",
                "你来补充",
                "帮我补充",
                "你来定",
                "你决定",
                "你看着办",
                "完全帮我",
                "全部帮我",
                "全都交给你",
                "自动补全"
        );
        boolean lackOfKnowledge = containsAny(
                requestDescription,
                "我一无所知",
                "我什么都不知道",
                "我不清楚",
                "我也不清楚",
                "我不了解"
        );
        boolean designerEntrustment = containsAny(
                requestDescription,
                "设计师一样",
                "像设计师一样",
                "帮我定",
                "替我定"
        );
        return explicitDelegation || (lackOfKnowledge && designerEntrustment);
    }

    private static boolean hasExplicitProductPosterDetailDesignStyle(String requestDescription) {
        return containsAny(
                requestDescription,
                "设计风格",
                "详情图风格",
                "详情页风格",
                "视觉风格",
                "版式风格",
                "排版风格",
                "杂志风",
                "科技极简",
                "极简风",
                "高级感",
                "小红书风",
                "淘宝风",
                "ins风",
                "海报风",
                "编辑感",
                "轻奢风",
                "高级留白",
                "克制奢华"
        );
    }

    private static String inferDetailDesignStyleFromResearch(String industryResearchContext) {
        String visualStyle = extractResearchField(industryResearchContext, "行业视觉风格");
        String trendStyle = extractResearchField(industryResearchContext, "流行趋势风格");
        String layoutPattern = extractResearchField(industryResearchContext, "详情页版式");
        List<String> parts = new ArrayList<>();
        if (hasStructuredValue(visualStyle)) {
            parts.add(visualStyle.trim());
        }
        if (hasStructuredValue(trendStyle)) {
            parts.add(trendStyle.trim());
        }
        if (hasStructuredValue(layoutPattern)) {
            parts.add(layoutPattern.trim());
        }
        if (parts.isEmpty()) {
            return "";
        }
        return safeLength(String.join("；", parts), 220);
    }

    private static String extractResearchField(String industryResearchContext, String label) {
        if (industryResearchContext == null || industryResearchContext.isBlank() || label == null || label.isBlank()) {
            return "";
        }
        int startIndex = industryResearchContext.indexOf(label + "：");
        if (startIndex < 0) {
            return "";
        }
        int valueStart = startIndex + label.length() + 1;
        int nextDivider = industryResearchContext.indexOf("；", valueStart);
        String rawValue = nextDivider >= 0
                ? industryResearchContext.substring(valueStart, nextDivider)
                : industryResearchContext.substring(valueStart);
        return safeLength(rawValue.trim(), 160);
    }

    private static String buildProductPosterResearchSeed(
            String requestDescription,
            ProductPosterVisionAnalysisService.VisionAnalysisResult visionAnalysis
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户需求：").append(productPosterText(requestDescription)).append("\n");
        if (visionAnalysis != null) {
            builder.append("图片观察：").append(productPosterText(visionAnalysis.summary())).append("\n");
            builder.append("图片直读事实：").append(formatProductPosterFacts(visionAnalysis.confirmedFacts())).append("\n");
            builder.append("图片候选建议：").append(formatProductPosterFacts(visionAnalysis.suggestedFacts())).append("\n");
        }
        builder.append("请按顶级品牌商品详情图设计师视角进行行业调研。");
        return builder.toString();
    }

    private static void relaxBlockingSlotsForAutonomousPlanning(
            String industry,
            AiProxyDtos.ProductPosterAnalysisResponse response,
            ProductPosterCategorySchema schema,
            Map<String, ProductPosterSlotDefinition> blockingMissing
    ) {
        if (blockingMissing.isEmpty()) {
            return;
        }
        blockingMissing.remove("material_or_composition");
        blockingMissing.remove("size_or_spec");
        if (schema != null && schema.childSlots() != null) {
            for (ProductPosterSlotDefinition childSlot : schema.childSlots()) {
                blockingMissing.remove(childSlot.key());
            }
        }
        if (hasAutonomousPlanningIdentity(industry, response)) {
            blockingMissing.remove("product_name");
        }
        if (hasAutonomousPlanningDirection(response)) {
            blockingMissing.remove("selling_points");
        }
    }

    private static boolean hasAutonomousPlanningIdentity(
            String industry,
            AiProxyDtos.ProductPosterAnalysisResponse response
    ) {
        return hasStructuredValue(response.productName())
                || hasStructuredValue(response.summary())
                || (industry != null && !industry.isBlank() && !PRODUCT_INDUSTRY_GENERAL.equals(industry))
                || hasAnyProductPosterFacts(response.confirmedFacts());
    }

    private static boolean hasAutonomousPlanningDirection(AiProxyDtos.ProductPosterAnalysisResponse response) {
        return hasStructuredValue(response.sellingPoints())
                || hasStructuredValue(response.style())
                || hasStructuredValue(response.scenarios())
                || hasStructuredValue(response.targetAudience())
                || hasAnyProductPosterFacts(response.suggestedFacts());
    }

    private static boolean hasAnyProductPosterFacts(List<AiProxyDtos.ProductPosterFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return false;
        }
        for (AiProxyDtos.ProductPosterFact fact : facts) {
            if (fact != null && hasStructuredValue(fact.value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank() || keywords == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAll(String text, String... keywords) {
        if (keywords == null || keywords.length == 0) {
            return true;
        }
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && !normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String combineProductPosterText(String... values) {
        List<String> parts = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    parts.add(value.trim());
                }
            }
        }
        return String.join(" ", parts);
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
            case "tool.image.panorama" -> new ForcedToolPlan("image-edit", "panorama", 1);
            case "tool.image.layer-subject" -> new ForcedToolPlan("image-edit", "layer-subject", 1);
            case "tool.image.layer-background" -> new ForcedToolPlan("image-edit", "layer-background", 1);
            case "tool.image.layer-split" -> new ForcedToolPlan(
                    "image-edit",
                    "layer-background".equals(normalizeRequestedMode(request.requestedEditMode(), request.prompt()))
                            ? "layer-background"
                            : "layer-subject",
                    1
            );
            case "tool.product.poster" -> new ForcedToolPlan("image-edit", "product-poster", normalizeProductPosterCount(request.productPoster()));
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

    private String getProductPosterPlannerSystemPrompt() {
        return """
                你是资深电商详情页视觉策划。你的任务是根据产品图和产品信息，规划一组可直接用于图生图的商品详情图方案。
                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"industry":"行业名称","audience":"目标人群","visualStrategy":"整体视觉策略","posters":[{"title":"不超过12字的详情图名称","purpose":"这张详情图的用途","cardType":"hero 或 selling-points 或 material-detail 或 size-spec 或 scenario 或 audience 或 series-overview 或 comparison 或 gift 或 usage","prompt":"可直接提交给图片编辑模型的中文提示词","headline":"主标题","subheading":"副标题或解释句","sellingBullets":["1到4个短卖点"],"tags":["若干短标签"],"sceneFocus":"场景焦点","layoutIntent":"版式意图","compositionDirectives":"构图与摆放要求","copyTone":"文案语气","avoid":"需要避免的设计","aspectRatio":"auto 或 1:1 或 4:3 或 3:4 或 16:9 或 9:16 或 2:1"}]}

                规划规则：
                - 输入中会给出“产品图模式”：single 表示单品模式，series 表示系列模式，必须严格按该模式规划。
                - single 单品模式：第 1 张 image 是主产品图；第 2 张及之后的 image 是同一产品的侧面、背面、细节、包装、材质或 logo 参考。每张详情图都必须把它们理解为同一个商品的不同参考，不要生成多个不同商品。
                - series 系列模式：每张 image 是同一产品系列中的不同产品/SKU/款式/颜色；必须保留每个产品的独立外观和差异，不要把多张图融合成一个商品，也不要只突出其中一个商品。详情图可以规划系列首屏、全系列陈列、款式对比、单品卖点、组合场景和套装价值。
                - single 模式 prompt 必须明确“以第 1 张 image 控制主体身份，并结合其他 image 补充细节；保持产品主体形状、颜色、材质、纹理、logo、结构和比例尽量不变”。
                - series 模式 prompt 必须明确“所有 image 是同一系列的不同产品；分别保留每个产品的形状、颜色、材质、图案和款式差异；根据详情图用途决定同框展示、对比展示或选择其中某个产品做单品卖点说明”。
                - 可以生成商业背景、场景、光影、道具、信息卡片、图标、分割线、详情页模块和营销视觉，但不要改变产品核心外观。
                - 每张详情图都要加入清晰可读的中文文字描述；允许高级留白，但不要只留空白而缺少有效信息。文字由“短标题 + 1 到 3 个短卖点（普通品类可到 4 个） + 材质/规格/适用场景等标签”组成。
                - 文字要短句化、模块化、排版整齐；单个短句尽量不超过 10 个汉字，不写长段落，不生成乱码、水印、二维码、假品牌或价格。
                - 如果详情图里出现人物、手、桌面、电脑、手机、水杯、家具、包装盒或其他参照物，必须让产品在画面中的视觉比例自然可信；优先依据用户提供的尺寸/规格/容量/适配信息，以及产品原图/包装上清晰可读的现有文字判断比例；如果未提供具体尺寸，只保持比例自然，不要反推出厚度、容量、尺寸数值或其他未提供参数。
                - %s
                - 必须在分析出产品信息后，优先结合 AI web search 行业调研结果，提炼该行业近期最前沿、最流行、最引人入胜且简洁优雅的设计风格和排版方式。
                - 如果输入里已经明确给出“详情图设计风格”，必须优先遵循该风格，不要被 web search 结果覆盖；只有用户没有指定时，才使用 web search 帮你补全详情图设计风格。
                - %s
                - %s
                - %s
                - 每张详情图都要具备“简洁优雅 + 高转化”目标：视觉第一眼高级干净，产品特点看得清楚，利益点表达明确，能勾起用户购买欲，但不要虚构价格、销量、认证或未提供参数。
                - prompt 里必须写清楚：采用哪种简洁优雅的流行视觉风格、版式结构、产品特点展示方法、购买欲钩子、主标题/卖点/标签区域如何安排。
                - 画面必须克制留白、层级清晰、文字少而准，避免廉价促销感、红黄爆炸贴、拥挤信息流、杂乱贴纸、过度装饰和低端直播间风格。
                - 必须先判断产品所在行业，再选择匹配行业的视觉风格和排版密度，不要所有商品都使用同一套电商模板：
                  * 香水、香氛、珠宝、艺术礼品：更有艺术气息，画面简洁，留白更多，光影高级，文字更少更克制，像精品画册或奢侈品详情页。
                  * 美妆、护肤：干净通透，柔和高光，强调成分、肤感、功效和质地，文字模块轻盈。
                  * 数码、AI、潮流贴纸：科技感、年轻化、桌搭场景，色块和图标更明确，文字可以更有节奏。
                  * 食品饮品：温暖、有食欲、场景真实，强调口味、原料、产地和新鲜感。
                  * 家居日用：生活方式、空间感、清爽可信，强调尺寸、材质、收纳或使用场景。
                  * 服饰鞋包：模特/穿搭/材质细节，强调版型、面料、搭配和场景。
                - 详情图之间要有明显差异：首屏主图、卖点说明、材质工艺、尺寸参数、场景应用、适用人群、包装/送礼等方向任选。
                - 可以根据用户描述归纳行业特点、消费场景、审美风格和适合平台，但不得据此补写任何未提供的产品事实参数。
                - 输出 poster 数量必须等于用户要求数量。
                """.formatted(PRODUCT_POSTER_FACT_GUARD_TEXT, PRODUCT_POSTER_TREND_STYLE_TEXT, PRODUCT_POSTER_TREND_LAYOUT_TEXT, PRODUCT_POSTER_TREND_AVOID_TEXT);
    }

    private String buildProductPosterPlannerInput(
            AiProxyDtos.ProductPosterRequest productPoster,
            List<AiProxyDtos.ImageReferenceCandidate> productImages,
            String aspectRatio,
            int count,
            String industryResearchContext
    ) {
        StringBuilder builder = new StringBuilder();
        boolean seriesMode = isSeriesProductPoster(productPoster);
        builder.append("产品事实锚点：\n");
        List<AiProxyDtos.ImageReferenceCandidate> images = productImages == null ? List.of() : productImages;
        for (int index = 0; index < images.size(); index += 1) {
            AiProxyDtos.ImageReferenceCandidate image = images.get(index);
            builder.append("- 第 ").append(index + 1).append(" 张 image：");
            builder.append(seriesMode ? "同一系列中的第 %d 个产品/SKU".formatted(index + 1) : index == 0 ? "主产品图" : "同一产品补充参考");
            builder.append("，画布名称=").append(productPosterText(image.name()));
            if (image.width() != null && image.height() != null) {
                builder.append("，尺寸=").append(image.width()).append("x").append(image.height());
            }
            builder.append("\n");
        }
        builder.append("产品图模式：").append(seriesMode ? "series（产品系列：多张 image 是同一系列不同产品/SKU，不要融合成一个商品）" : "single（单品：多张 image 是同一产品的不同角度/细节参考）").append("\n");
        builder.append("目标画幅：").append(normalizeProductPosterAspectRatio(aspectRatio)).append("\n");
        builder.append("需要详情图数量：").append(count).append("\n");
        builder.append("原始产品描述：").append(productPosterText(productPoster == null ? "" : productPoster.productDescription())).append("\n");
        builder.append("产品名称：").append(productPosterText(productPoster == null ? "" : productPoster.productName())).append("\n");
        builder.append("产品行业：").append(productPosterText(productPoster == null ? "" : productPoster.industry())).append("\n");
        builder.append("材质：").append(productPosterText(productPoster == null ? "" : productPoster.material())).append("\n");
        builder.append("大小/规格：").append(productPosterText(productPoster == null ? "" : productPoster.size())).append("\n");
        builder.append("颜色：").append(productPosterText(productPoster == null ? "" : productPoster.color())).append("\n");
        builder.append("款式/风格：").append(productPosterText(productPoster == null ? "" : productPoster.style())).append("\n");
        builder.append("详情图设计风格：").append(productPosterText(productPoster == null ? "" : productPoster.detailDesignStyle())).append("\n");
        builder.append("适用场景：").append(productPosterText(productPoster == null ? "" : productPoster.scenarios())).append("\n");
        builder.append("使用人群：").append(productPosterText(productPoster == null ? "" : productPoster.targetAudience())).append("\n");
        builder.append("核心卖点：").append(productPosterText(productPoster == null ? "" : productPoster.sellingPoints())).append("\n");
        builder.append("补充参数：").append(productPosterText(productPoster == null ? "" : productPoster.extraDetails())).append("\n");
        builder.append("平台风格：").append(productPosterText(productPoster == null ? "" : productPoster.platformStyle())).append("\n");
        builder.append("商品详情图对话上下文：").append(productPosterText(buildProductPosterConversationContext(productPoster))).append("\n");
        builder.append("卡片路由建议：").append(formatProductPosterCardRouting(buildRecommendedProductPosterCards(productPoster, count))).append("\n");
        builder.append("创意与版式约束：\n");
        builder.append("- ").append(PRODUCT_POSTER_FACT_GUARD_TEXT).append("\n");
        builder.append("- 真实比例要求：如果详情图里出现人物、手、桌面、电脑、手机、水杯、家具、包装盒或其他参照物，必须让产品在画面中的视觉比例自然可信；优先依据已提供的尺寸/规格/容量/适配信息，以及产品原图/包装上清晰可读的现有文字判断比例；如果未提供具体尺寸，只保持比例自然，不要反推出厚度、容量、尺寸数值或其他未提供参数。\n");
        builder.append("- ").append(PRODUCT_POSTER_TREND_STYLE_TEXT).append("\n");
        builder.append("- ").append(PRODUCT_POSTER_TREND_LAYOUT_TEXT).append("\n");
        builder.append("- ").append(PRODUCT_POSTER_TREND_AVOID_TEXT).append("\n");
        if (conversationContextPresent(productPoster)) {
            builder.append("- 请继承上面对话里已经确认过的产品事实、风格偏好、卖点重点、排版要求和追问结论，不要遗漏。\n");
        }
        if (industryResearchContext != null && !industryResearchContext.isBlank()) {
            builder.append("AI web search 行业调研：").append(productPosterText(industryResearchContext)).append("\n");
            builder.append("请先分析产品本身，再把上面的 websearch 结果用于确定最前沿/最流行/最引人入胜但简洁优雅的视觉风格、高转化详情页版式、产品特点展示方法、购买欲钩子、道具场景、文案角度和需要避免的设计，不要照搬搜索文本。\n");
        } else {
            builder.append("AI web search 行业调研：未获取到可用结果，请使用内置行业经验判断最流行且简洁优雅的行业审美、高转化版式、产品特点展示方法和购买欲钩子。\n");
        }
        return builder.toString();
    }

    private List<ProductPosterPlanItem> parseProductPosterPlan(String responseText, int count, String fallbackAspectRatio) throws IOException {
        JsonNode data = objectMapper.readTree(extractJsonObjectText(responseText));
        JsonNode postersNode = data.get("posters");
        if (postersNode == null || !postersNode.isArray()) {
            return List.of();
        }

        List<ProductPosterPlanItem> items = new ArrayList<>();
        for (JsonNode posterNode : postersNode) {
            if (!posterNode.isObject()) {
                continue;
            }
            String title = sanitizeProductPosterTitle(readJsonTextField(posterNode, "title"), items.size() + 1);
            String purpose = safeLength(readJsonTextField(posterNode, "purpose"), 160);
            String prompt = safeLength(readJsonTextField(posterNode, "prompt"), 1600);
            String aspectRatio = normalizeProductPosterAspectRatio(firstNonBlank(readJsonTextField(posterNode, "aspectRatio"), fallbackAspectRatio));
            if (prompt.isBlank()) {
                continue;
            }
            items.add(new ProductPosterPlanItem(
                    title,
                    purpose,
                    prompt,
                    aspectRatio,
                    normalizeProductPosterCardType(firstNonBlank(readJsonTextField(posterNode, "cardType"), cardTypeFromTitle(title, items.size()))),
                    safeLength(readJsonTextField(posterNode, "headline"), 120),
                    safeLength(readJsonTextField(posterNode, "subheading"), 160),
                    readJsonTextListField(posterNode, "sellingBullets", 4),
                    readJsonTextListField(posterNode, "tags", 6),
                    safeLength(readJsonTextField(posterNode, "sceneFocus"), 160),
                    safeLength(readJsonTextField(posterNode, "layoutIntent"), 240),
                    safeLength(readJsonTextField(posterNode, "compositionDirectives"), 240),
                    safeLength(readJsonTextField(posterNode, "copyTone"), 120),
                    safeLength(readJsonTextField(posterNode, "avoid"), 160)
            ));
            if (items.size() >= count) {
                break;
            }
        }
        return List.copyOf(items);
    }

    private List<ProductPosterPlanItem> buildFallbackProductPosterPlan(
            AiProxyDtos.ProductPosterRequest productPoster,
            int count,
            String aspectRatio
    ) {
        String productName = firstNonBlank(productPoster == null ? "" : productPoster.productName(), "产品");
        String productInfo = buildProductPosterInfo(productPoster);
        String normalizedAspectRatio = normalizeProductPosterAspectRatio(aspectRatio);
        List<ProductPosterPlanItem> templates = new ArrayList<>();
        for (String cardType : buildRecommendedProductPosterCards(productPoster, count)) {
            templates.add(buildFallbackProductPosterPlanItem(productName, productInfo, normalizedAspectRatio, cardType, templates.size() + 1));
        }
        while (templates.size() < count) {
            templates.add(buildFallbackProductPosterPlanItem(productName, productInfo, normalizedAspectRatio, cardTypeFromTitle("详情图" + (templates.size() + 1), templates.size()), templates.size() + 1));
        }
        return List.copyOf(templates.subList(0, Math.min(count, templates.size())));
    }

    private ProductPosterPlanItem buildFallbackProductPosterPlanItem(
            String productName,
            String productInfo,
            String aspectRatio,
            String cardType,
            int index
    ) {
        String normalizedCardType = normalizeProductPosterCardType(cardType);
        return switch (normalizedCardType) {
            case "hero" -> new ProductPosterPlanItem(
                    "首屏详情图",
                    "突出产品第一眼吸引力",
                    "为%s生成简洁优雅的商品详情图首屏，突出主体轮廓、材质、质感与核心卖点。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    productName,
                    "第一眼就看清产品价值",
                    List.of("产品主体清晰", "核心卖点醒目", "留白高级"),
                    List.of("首屏", "主视觉", "高转化"),
                    "让产品处于画面第一焦点",
                    "杂志式首屏，标题区与产品区分明",
                    "产品绝对主角，大面积留白，层级清晰",
                    "简洁、高级、克制",
                    "不要廉价促销感和密集信息"
            );
            case "selling-points" -> new ProductPosterPlanItem(
                    "卖点详情图",
                    "强调材质和核心卖点",
                    "为%s生成卖点说明详情图，突出产品材质、结构、使用价值和购买理由。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "核心卖点",
                    "让用户快速理解为什么值得买",
                    List.of("材质质感", "使用价值", "购买理由"),
                    List.of("卖点", "材质", "功能"),
                    "产品细节与卖点模块",
                    "信息卡片分区清晰，文字少而准",
                    "近景展示产品细节，避免拥挤",
                    "理性但有吸引力",
                    "不要写长段参数和空泛口号"
            );
            case "material-detail" -> new ProductPosterPlanItem(
                    "材质工艺图",
                    "说明材质、工艺和质感",
                    "为%s生成材质工艺详情图，突出表面纹理、做工、印刷或结构细节。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "材质与工艺",
                    "把细节做成购买理由",
                    List.of("质感清晰", "局部放大", "细节可感知"),
                    List.of("材质", "工艺", "细节"),
                    "产品局部细节与工艺展示",
                    "局部放大 + 标签说明",
                    "细节放大但主体身份稳定",
                    "专业、可信、有质感",
                    "不要杜撰工艺参数"
            );
            case "size-spec" -> new ProductPosterPlanItem(
                    "尺寸参数图",
                    "说明规格和适配信息",
                    "为%s生成规格与适配说明图，仅使用用户明确提供的规格信息。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "规格与适配",
                    "参数只展示已确认信息",
                    List.of("尺寸清晰", "适配明确", "标签整齐"),
                    List.of("规格", "适配", "参数"),
                    "规格说明与场景比例展示",
                    "极简参数排版，避免表格拥挤",
                    "如果没有明确尺寸就不用具体数字",
                    "克制、清晰、可读",
                    "不要虚构任何数字参数"
            );
            case "scenario" -> new ProductPosterPlanItem(
                    "场景说明图",
                    "展示真实使用场景",
                    "为%s生成真实使用场景详情图，用场景证明产品价值。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "使用场景",
                    "让用户代入真实使用环境",
                    List.of("场景真实", "产品清晰", "价值明确"),
                    List.of("场景", "应用", "代入感"),
                    "真实使用场景与产品关系",
                    "产品与场景分层明确，文字辅助解释",
                    "场景为产品服务，不喧宾夺主",
                    "自然、可信、生活化",
                    "不要背景过满或抢主角"
            );
            case "audience" -> new ProductPosterPlanItem(
                    "人群场景图",
                    "说明适用人群和情绪价值",
                    "为%s生成适用人群详情图，表达适合谁以及为什么适合。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "适合谁用",
                    "让目标人群一眼对号入座",
                    List.of("适用人群", "情绪价值", "购买理由"),
                    List.of("人群", "场景", "价值"),
                    "目标用户与产品关系",
                    "标题简洁，标签模块化",
                    "人群表达明确但画面不过度表演",
                    "友好、克制、有共鸣",
                    "不要空泛标签堆砌"
            );
            case "series-overview" -> new ProductPosterPlanItem(
                    "系列展示图",
                    "展示同系列产品全貌",
                    "为%s生成系列首屏详情图，清楚展示系列产品的差异与组合价值。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "系列全览",
                    "让用户看清不同 SKU 的差异",
                    List.of("系列陈列", "配色差异", "组合价值"),
                    List.of("系列", "SKU", "对比"),
                    "多 SKU 同框陈列",
                    "系列产品整齐排布，层级清晰",
                    "每个产品都要可识别，不要融合",
                    "高级、有序、清楚",
                    "不要只突出其中一个产品"
            );
            case "comparison" -> new ProductPosterPlanItem(
                    "款式对比图",
                    "展示不同款式或颜色差异",
                    "为%s生成对比型详情图，清楚展示不同款式、颜色或版本的差异。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    "款式对比",
                    "差异一眼看懂",
                    List.of("差异清晰", "信息对齐", "选择边界明确"),
                    List.of("对比", "差异", "选择"),
                    "SKU 对比与选择说明",
                    "左右或上下对比，模块整齐",
                    "对比关系明确，避免互相遮挡",
                    "清晰、理性、利落",
                    "不要让对比变成拼贴混乱"
            );
            default -> new ProductPosterPlanItem(
                    "详情方案" + index,
                    "展示产品核心价值",
                    "为%s生成简洁优雅的商品详情图。产品信息：%s。".formatted(productName, productInfo),
                    aspectRatio,
                    normalizedCardType,
                    productName,
                    "突出产品核心价值",
                    List.of("主体清晰", "卖点明确"),
                    List.of("详情图"),
                    "围绕产品主价值展开",
                    "杂志式排版，信息层级清楚",
                    "产品清晰可信，文字少而准",
                    "简洁、专业",
                    "不要虚构参数"
            );
        };
    }

    private static boolean conversationContextPresent(AiProxyDtos.ProductPosterRequest productPoster) {
        return !buildProductPosterConversationContext(productPoster).isBlank();
    }

    private static String formatProductPosterCardRouting(List<String> cardTypes) {
        if (cardTypes == null || cardTypes.isEmpty()) {
            return "hero（首屏主图）";
        }
        List<String> labels = new ArrayList<>();
        for (String cardType : cardTypes) {
            labels.add(switch (normalizeProductPosterCardType(cardType)) {
                case "hero" -> "hero（首屏主图）";
                case "selling-points" -> "selling-points（卖点说明）";
                case "material-detail" -> "material-detail（材质工艺）";
                case "size-spec" -> "size-spec（规格参数）";
                case "scenario" -> "scenario（场景应用）";
                case "audience" -> "audience（适用人群）";
                case "series-overview" -> "series-overview（系列首屏）";
                case "comparison" -> "comparison（款式对比）";
                case "gift" -> "gift（送礼价值）";
                case "usage" -> "usage（使用方式）";
                default -> normalizeProductPosterCardType(cardType);
            });
        }
        return String.join(" -> ", labels);
    }

    private List<String> buildRecommendedProductPosterCards(AiProxyDtos.ProductPosterRequest productPoster, int count) {
        boolean seriesMode = isSeriesProductPoster(productPoster);
        boolean hasSizeInfo = hasStructuredValue(productPoster == null ? "" : productPoster.size())
                || containsAny(productPoster == null ? "" : productPoster.extraDetails(), "尺寸", "规格", "容量", "毫米", "厘米", "ml", "L", "适配", "型号");
        List<String> candidates = new ArrayList<>();
        if (seriesMode) {
            candidates.add("series-overview");
            candidates.add("comparison");
            candidates.add("selling-points");
            candidates.add("scenario");
            candidates.add(hasSizeInfo ? "size-spec" : "audience");
        } else {
            candidates.add("hero");
            candidates.add("selling-points");
            candidates.add("material-detail");
            candidates.add(hasSizeInfo ? "size-spec" : "scenario");
            candidates.add(hasSizeInfo ? "scenario" : "audience");
            candidates.add("audience");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String candidate : candidates) {
            unique.add(normalizeProductPosterCardType(candidate));
            if (unique.size() >= Math.max(1, count)) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private static String normalizeProductPosterCardType(String value) {
        String normalized = normalizeToken(value).replace('_', '-');
        return switch (normalized) {
            case "hero", "cover", "main" -> "hero";
            case "selling-points", "sellingpoints", "selling-point", "features", "feature" -> "selling-points";
            case "material-detail", "materialdetail", "material", "craft", "craft-detail" -> "material-detail";
            case "size-spec", "sizespec", "spec", "size", "parameter", "params" -> "size-spec";
            case "scenario", "scene", "usage-scene" -> "scenario";
            case "audience", "people", "target-audience" -> "audience";
            case "series-overview", "seriesoverview", "series" -> "series-overview";
            case "comparison", "compare", "contrast" -> "comparison";
            case "gift", "gift-scene" -> "gift";
            case "usage", "how-to-use" -> "usage";
            default -> normalized.isBlank() ? "hero" : normalized;
        };
    }

    private static String cardTypeFromTitle(String title, int index) {
        String normalized = title == null ? "" : title;
        if (containsAny(normalized, "首屏", "主图", "封面")) {
            return "hero";
        }
        if (containsAny(normalized, "卖点", "优势")) {
            return "selling-points";
        }
        if (containsAny(normalized, "材质", "工艺", "细节")) {
            return "material-detail";
        }
        if (containsAny(normalized, "尺寸", "规格", "参数", "适配")) {
            return "size-spec";
        }
        if (containsAny(normalized, "场景", "应用")) {
            return "scenario";
        }
        if (containsAny(normalized, "人群", "送礼")) {
            return "audience";
        }
        if (containsAny(normalized, "系列")) {
            return "series-overview";
        }
        if (containsAny(normalized, "对比")) {
            return "comparison";
        }
        return index == 0 ? "hero" : "selling-points";
    }

    private List<String> readJsonTextListField(JsonNode data, String fieldName, int maxItems) {
        JsonNode value = data.get(fieldName);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                continue;
            }
            String text = safeLength(item.asText().trim(), 80);
            if (text.isBlank() || values.contains(text)) {
                continue;
            }
            values.add(text);
            if (values.size() >= Math.max(1, maxItems)) {
                break;
            }
        }
        return List.copyOf(values);
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
                - 多角度 / 改视角 / 整图视角变化 / 摄像头视角变化
                - 完整球形全景 / equirectangular 全景图
                - 商品详情图生成
                - 画布、项目、导出、下载、画幅比例、参考图、提示词优化等 livart 功能说明

                你现在只会收到已经被上一步判定为“生图”的请求。不要执行内容安全审核，不要拒绝，不要判断是否能生成；只负责把请求规划成图片任务，最终能否生成由后续生图接口判断。
                只输出严格 JSON，不要 Markdown，不要解释，不要额外字段：
                {"allowed":true或false,"responseMode":"execute 或 answer 或 reject","rejectionMessage":"如果拒答，这里给出简短中文引导；否则为空字符串","answerMessage":"如果是 livart 功能问答，这里给出简短中文回答；否则为空字符串","taskType":"text-to-image 或 image-edit","mode":"generate 或 edit 或 background-removal 或 remover 或 layer-subject 或 layer-background 或 view-change 或 panorama 或 product-poster","count":1到4的整数,"baseImageId":"候选图片 id，没有就空字符串","referenceImageIds":["其他候选图片 id"],"displayTitle":"给这次图片任务生成的简短中文标题","displayMessage":"展示给用户的一句自然回复","thinkingSteps":["步骤1","步骤2","步骤3"]}

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
                - mode=view-change 表示基于原图完整画面生成新的拍摄角度、整图视角或相机位姿变化；相机围绕固定场景移动，不是只旋转单个主体。
                - mode=panorama 表示把原图转换成 2:1 的完整球形全景 / equirectangular panorama，也就是 360° 水平视角 + 180° 垂直视角；保持原图场景和物体不变，只补全环绕空间。
                - mode=product-poster 表示基于产品图生成商品详情图、电商详情页首屏、卖点说明图或营销主图。
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
                - 问答：用户在询问 livart 的功能、用法、导出、下载、画幅、参考图、画布、项目、登录、统计、部署、工具按钮、局部重绘、删除物体、去背景、图层拆分、多角度、全景图，或询问“你是谁”“你叫什么”“你好”等站内助手问题。
                - 生图：用户明确要生成图片、编辑图片、局部重绘、删除物体、去背景、抠图、图层拆分、提取主体层、生成背景层、改视角、多角度、整图视角变化、相机位姿变化、摄像头角度变化、全景图、360°全景、球形全景、商品详情图、产品详情图、产品海报、商品海报、电商主图、营销海报、产品场景图、商品广告图、改图、换背景、换物体，或者输入本身就是明显的画面描述 / 生图提示词。
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
                    new AiProxyDtos.AgentPlanStep("identify-view-change", "识别视角", "确认原图完整场景与目标旋转、倾斜和缩放参数。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-view-change", "规划视角", "生成保持整张图内容一致的新视角编辑指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-view-change", "执行多角度", "调用图片编辑接口输出新角度结果。", "edit")
            );
        }

        if ("panorama".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("identify-panorama", "识别场景", "确认原图主体、空间结构、光影和需要延展的环境。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("optimize-panorama", "规划全景", "生成保持原图内容一致的完整球形全景指令。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-panorama", "执行全景化", "调用图片编辑接口输出 2:1 全景结果。", "edit")
            );
        }

        if ("product-poster".equals(mode)) {
            return List.of(
                    new AiProxyDtos.AgentPlanStep("analyze-product", "分析产品", "识别产品信息、行业特点、目标人群和使用场景。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("research-industry", "WebSearch 行业调研", "检索行业趋势、竞品详情图、社媒种草视觉和高转化排版参考。", "analysis"),
                    new AiProxyDtos.AgentPlanStep("plan-poster-set", "规划详情图", "规划多张商品详情图的视觉方向、文字描述和卖点表达。", "prompt"),
                    new AiProxyDtos.AgentPlanStep("run-product-posters", "生成详情图", "并行创建商品详情图图片任务。", "edit")
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
            case "panorama" -> "360全景图";
            case "product-poster" -> "商品详情图";
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
        if ("panorama".equals(mode)) {
            return "我将为您把这张%s转换成完整球形全景图。".formatted(title);
        }
        if ("product-poster".equals(mode)) {
            return count > 1
                    ? "我将为您生成%d张%s。".formatted(count, title)
                    : "我将为您生成这张%s。".formatted(title);
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
        if ("panorama".equals(mode)) {
            return "360全景图";
        }
        if ("product-poster".equals(mode)) {
            return "商品详情图";
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
        if ("panorama".equals(mode)) {
            return "360全景图";
        }
        if ("product-poster".equals(mode)) {
            return "商品详情图";
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
            return List.of("识别整张图视角", "规划角度参数", "准备生成整图新视角");
        }
        if ("panorama".equals(mode)) {
            return List.of("识别原图场景", "规划 360° 全景延展", "准备生成 2:1 全景图");
        }
        if ("product-poster".equals(mode)) {
            return List.of("分析产品行业", "规划详情图文案", "准备并行生成");
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

    private static String selectProductPosterBaseImageId(
            AiProxyDtos.ProductPosterRequest productPoster,
            String contextImageId,
            List<AiProxyDtos.ImageReferenceCandidate> images
    ) {
        for (String productImageId : normalizedProductPosterImageIds(productPoster)) {
            for (AiProxyDtos.ImageReferenceCandidate image : images) {
                if (productImageId.equals(image.id())) {
                    return image.id();
                }
            }
        }
        return selectFallbackBaseImageId(contextImageId, images);
    }

    private static List<String> selectProductPosterReferenceImageIds(
            AiProxyDtos.ProductPosterRequest productPoster,
            String baseImageId,
            List<AiProxyDtos.ImageReferenceCandidate> images
    ) {
        Set<String> availableImageIds = new LinkedHashSet<>();
        for (AiProxyDtos.ImageReferenceCandidate image : images) {
            availableImageIds.add(image.id());
        }
        List<String> requestedImageIds = normalizedProductPosterImageIds(productPoster);
        if (requestedImageIds.isEmpty()) {
            requestedImageIds = images.stream().map(AiProxyDtos.ImageReferenceCandidate::id).toList();
        }
        LinkedHashSet<String> referenceImageIds = new LinkedHashSet<>();
        for (String imageId : requestedImageIds) {
            if (imageId != null && !imageId.equals(baseImageId) && availableImageIds.contains(imageId)) {
                referenceImageIds.add(imageId);
            }
        }
        return List.copyOf(referenceImageIds);
    }

    private static List<String> normalizedProductPosterImageIds(AiProxyDtos.ProductPosterRequest productPoster) {
        return productPoster == null ? List.of() : productPoster.normalizedProductImageIds();
    }

    private static int normalizeProductPosterCount(AiProxyDtos.ProductPosterRequest productPoster) {
        int count = productPoster == null || productPoster.posterCount() == null ? 3 : productPoster.posterCount();
        return Math.max(1, Math.min(6, count));
    }

    private static boolean isSeriesProductPoster(AiProxyDtos.ProductPosterRequest productPoster) {
        return productPoster != null && "series".equals(productPoster.normalizedProductMode());
    }

    private static String normalizeProductPosterAspectRatio(String aspectRatio) {
        String normalized = normalizeAspectRatio(aspectRatio);
        return "auto".equals(normalized) ? "3:4" : normalized;
    }

    private static String normalizeProductPosterPlatformStyle(String platformStyle) {
        String normalized = platformStyle == null ? "" : platformStyle.trim();
        return switch (normalized) {
            case "淘宝/天猫", "抖音电商", "小红书种草", "朋友圈", "独立站", "通用商业" -> normalized;
            default -> "通用商业";
        };
    }

    private static String productPosterText(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? "未填写" : safeLength(normalized, 800);
    }

    private static String buildProductPosterConversationContext(AiProxyDtos.ProductPosterRequest productPoster) {
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
        return safeLength(String.join("\n", lines), 3000);
    }

    private static String buildProductPosterInfo(AiProxyDtos.ProductPosterRequest productPoster) {
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
        appendProductPosterField(fields, "详情图设计风格", productPoster.detailDesignStyle());
        appendProductPosterField(fields, "适用场景", productPoster.scenarios());
        appendProductPosterField(fields, "使用人群", productPoster.targetAudience());
        appendProductPosterField(fields, "核心卖点", productPoster.sellingPoints());
        appendProductPosterField(fields, "补充参数", productPoster.extraDetails());
        appendProductPosterField(fields, "平台风格", productPoster.platformStyle());
        return fields.isEmpty()
                ? "用户未填写详细产品信息；仅可根据产品图识别可见品类与外观线索，不要补写未提供的厚度、尺寸、容量、适配、认证、工艺或其他参数。"
                : String.join("；", fields);
    }

    private static void appendProductPosterField(List<String> fields, String label, String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank()) {
            fields.add(label + "：" + safeLength(normalized, 300));
        }
    }

    private static String sanitizeProductPosterTitle(String value, int index) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("[，。,.；;：:、\\s]+$", "");
        if (normalized.length() > 12) {
            normalized = normalized.substring(0, 12);
        }
        return normalized.isBlank() ? "详情方案" + index : normalized;
    }

    private static String safeLength(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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

        if ("layer-subject".equals(requestedMode) || "layer-background".equals(requestedMode) || "view-change".equals(requestedMode) || "panorama".equals(requestedMode) || "product-poster".equals(requestedMode)) {
            return requestedMode;
        }

        String normalized = normalizeToken(value);
        return switch (normalized) {
            case "backgroundremoval", "background-removal", "removebackground" -> "background-removal";
            case "remover", "image-remover", "objectremoval", "object-removal" -> "remover";
            case "layersubject", "layer-subject", "subjectlayer", "subject-layer" -> "layer-subject";
            case "layerbackground", "layer-background", "backgroundlayer", "background-layer" -> "layer-background";
            case "viewchange", "view-change", "multiangle", "multi-angle", "anglechange", "angle-change", "perspectivechange", "perspective-change" -> "view-change";
            case "panorama", "sphericalpanorama", "spherical-panorama", "equirectangular", "360", "360panorama", "360-panorama" -> "panorama";
            case "productposter", "product-poster", "poster", "commercialposter", "commercial-poster", "productdetail", "product-detail", "detailimage", "detail-image" -> "product-poster";
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
        if ("panorama".equals(normalized) || "sphericalpanorama".equals(normalized) || "spherical-panorama".equals(normalized) || "equirectangular".equals(normalized) || "360".equals(normalized) || "360panorama".equals(normalized) || "360-panorama".equals(normalized)) {
            return "panorama";
        }
        if ("productposter".equals(normalized) || "product-poster".equals(normalized) || "poster".equals(normalized)) {
            return "product-poster";
        }
        String promptText = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (promptText.contains("全景") || promptText.contains("360°") || promptText.contains("360度") || promptText.contains("equirectangular") || promptText.contains("panorama")) {
            return "panorama";
        }
        if (promptText.contains("产品海报") || promptText.contains("商品海报") || promptText.contains("电商主图") || promptText.contains("营销海报") || promptText.contains("商品详情图") || promptText.contains("产品详情图") || promptText.contains("电商详情图") || promptText.contains("详情页图")) {
            return "product-poster";
        }
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
            case "1:1", "4:3", "3:4", "16:9", "9:16", "2:1" -> aspectRatio.trim();
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

    public record ProductPosterPlanItem(
            String title,
            String purpose,
            String prompt,
            String aspectRatio,
            String cardType,
            String headline,
            String subheading,
            List<String> sellingBullets,
            List<String> tags,
            String sceneFocus,
            String layoutIntent,
            String compositionDirectives,
            String copyTone,
            String avoid
    ) {
        public ProductPosterPlanItem {
            sellingBullets = sellingBullets == null ? List.of() : List.copyOf(sellingBullets);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public ProductPosterPlanItem(
                String title,
                String purpose,
                String prompt,
                String aspectRatio
        ) {
            this(title, purpose, prompt, aspectRatio, "hero", "", "", List.of(), List.of(), "", "", "", "", "");
        }
    }

    private record ForcedToolPlan(
            String taskType,
            String mode,
            int count
    ) {
    }
}
