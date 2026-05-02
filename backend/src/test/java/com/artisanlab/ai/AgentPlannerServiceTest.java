package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPlannerServiceTest {
    @Test
    void fallbackPlanUsesContextImageAsBaseForEditTasks() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "把这张图里的鞋子换成红色的",
                "img-b",
                "9:16",
                "",
                "",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("img-a", "参考鞋子", 1, 300, 400),
                        new AiProxyDtos.ImageReferenceCandidate("img-b", "主图人物", 2, 512, 768)
                )
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.buildFallbackPlan(request);

        assertThat(plan.taskType()).isEqualTo("image-edit");
        assertThat(plan.mode()).isEqualTo("edit");
        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("execute");
        assertThat(plan.baseImageId()).isEqualTo("img-b");
        assertThat(plan.referenceImageIds()).containsExactly("img-a");
        assertThat(plan.source()).isEqualTo("fallback");
    }

    @Test
    void normalizePlanFiltersInvalidIdsAndBuildsBackgroundRemovalPlan() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "帮我给这张图去背景",
                "img-main",
                "auto",
                "",
                "",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("img-main", "主体图", 1, 512, 768),
                        new AiProxyDtos.ImageReferenceCandidate("img-ref", "辅助图", 2, 400, 400)
                )
        );

        AgentPlannerService.RawAgentPlan rawPlan = new AgentPlannerService.RawAgentPlan(
                true,
                "execute",
                "",
                "",
                "image-edit",
                "background-removal",
                1,
                "not-exists",
                List.of("img-main", "img-ref", "img-ref", "ghost"),
                "主体去背景",
                "我开始为这张主体去背景图片去背景。",
                List.of("识别主体", "判断去背景", "准备执行")
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.normalizePlan(rawPlan, request, "ai");

        assertThat(plan.taskType()).isEqualTo("image-edit");
        assertThat(plan.mode()).isEqualTo("background-removal");
        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("execute");
        assertThat(plan.baseImageId()).isEqualTo("img-main");
        assertThat(plan.referenceImageIds()).containsExactly("img-ref");
        assertThat(plan.steps()).hasSize(3);
        assertThat(plan.summary()).doesNotContain("@img-main");
        assertThat(plan.displayTitle()).isEqualTo("主体去背景");
        assertThat(plan.displayMessage()).isEqualTo("我开始为这张主体去背景图片去背景。");
        assertThat(plan.thinkingSteps()).containsExactly("识别主体", "判断去背景", "准备执行");
        assertThat(plan.source()).isEqualTo("ai");
    }

    @Test
    void normalizePlanFallsBackToTextToImageWhenNoImagesExist() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "一只在赛博朋克城市里吃臭豆腐的猫",
                "",
                "1:1",
                "",
                "",
                List.of()
        );

        AgentPlannerService.RawAgentPlan rawPlan = new AgentPlannerService.RawAgentPlan(
                true,
                "execute",
                "",
                "",
                "image-edit",
                "edit",
                3,
                "img-1",
                List.of("img-2"),
                "赛博朋克小猫",
                "我开始为你生成 3 张赛博朋克小猫图片。",
                List.of()
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.normalizePlan(rawPlan, request, "ai");

        assertThat(plan.taskType()).isEqualTo("text-to-image");
        assertThat(plan.mode()).isEqualTo("generate");
        assertThat(plan.baseImageId()).isEmpty();
        assertThat(plan.referenceImageIds()).isEmpty();
        assertThat(plan.aspectRatio()).isEqualTo("1:1");
        assertThat(plan.thinkingSteps()).containsExactly("分析画面需求", "整理生成提示词", "准备开始生图");
        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("execute");
        assertThat(plan.displayTitle()).isEqualTo("赛博朋克小猫");
        assertThat(plan.displayMessage()).isEqualTo("我开始为你生成 3 张赛博朋克小猫图片。");
    }

    @Test
    void normalizePlanHidesInternalProcessDisplayMessage() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "生成 3 张不同角度的猫咪照片",
                "",
                "1:1",
                "",
                "",
                List.of()
        );

        AgentPlannerService.RawAgentPlan rawPlan = new AgentPlannerService.RawAgentPlan(
                true,
                "execute",
                "",
                "",
                "text-to-image",
                "generate",
                3,
                "",
                List.of(),
                "不同角度的猫咪照片",
                "我已判断这是一次文生图任务，接下来会先整理提示词，再生成最终图片。",
                List.of("分析需求", "规划任务", "准备生成")
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.normalizePlan(rawPlan, request, "ai");

        assertThat(plan.displayMessage()).isEqualTo("我将为您生成3张不同角度的猫咪照片。");
    }

    @Test
    void normalizePlanRejectsAttributeOnlyDisplayTitleAndUsesCentralIdea() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "女生 23岁 178cm 90斤 皮肤白皙 抖音网红脸 晚上 劳斯莱斯 坐在车里 后排 车门打开 看着镜头 白色吊带",
                "",
                "9:16",
                "",
                "",
                List.of()
        );

        AgentPlannerService.RawAgentPlan rawPlan = new AgentPlannerService.RawAgentPlan(
                true,
                "execute",
                "",
                "",
                "text-to-image",
                "generate",
                1,
                "",
                List.of(),
                "女性岁身高斤皮肤白皙抖音",
                "",
                List.of("分析画面", "整理标题", "准备生成")
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.normalizePlan(rawPlan, request, "ai");

        assertThat(plan.displayTitle()).isEqualTo("夜景车内人像");
        assertThat(plan.displayMessage()).isEqualTo("我将为您生成这张夜景车内人像。");
    }

    @Test
    void fallbackPlanAnswersIdentityPrompt() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "你是谁",
                "",
                "auto",
                "",
                "",
                List.of()
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.buildFallbackPlan(request);

        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("answer");
        assertThat(plan.answerMessage()).contains("livart");
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void normalizePlanReturnsHelpAnswerForFeatureQuestions() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "怎么导出项目图片？",
                "",
                "auto",
                "",
                "",
                List.of()
        );

        AgentPlannerService.RawAgentPlan rawPlan = new AgentPlannerService.RawAgentPlan(
                true,
                "answer",
                "",
                "你可以在项目里直接下载当前选中的图片，未选中时会下载全部成品。",
                "",
                "",
                1,
                "",
                List.of(),
                "",
                "",
                List.of()
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.normalizePlan(rawPlan, request, "ai");

        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("answer");
        assertThat(plan.answerMessage()).contains("下载");
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void fallbackPlanReturnsHelpAnswerForFeatureQuestion() {
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "livart 怎么导出图片？",
                "",
                "auto",
                "",
                "",
                List.of()
        );

        AiProxyDtos.AgentPlanResponse plan = AgentPlannerService.buildFallbackPlan(request);

        assertThat(plan.allowed()).isTrue();
        assertThat(plan.responseMode()).isEqualTo("answer");
        assertThat(plan.answerMessage()).contains("导出");
        assertThat(plan.steps()).isEmpty();
    }

    @Test
    void createPlanUsesAiIntentClassifierForIdentityPrompt() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "你是谁",
                "",
                "auto",
                "",
                "",
                List.of()
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("agent-intent-classifier")
        )).thenReturn("问答");
        when(knowledgeAnswerService.answerSystemQuestion(
                eq(config),
                eq("你是谁"),
                anyString()
        )).thenReturn("我是 livart 站内 AI 图像创作助手，可以帮你生成和编辑图片。");

        AiProxyDtos.AgentPlanResponse plan = service.createPlan(userId, request);

        assertThat(plan.responseMode()).isEqualTo("answer");
        assertThat(plan.answerMessage()).contains("livart");
        assertThat(plan.source()).isEqualTo("ai");
        verify(knowledgeAnswerService).answerSystemQuestion(eq(config), eq("你是谁"), anyString());
        verify(springAiTextService, times(1)).completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("agent-intent-classifier")
        );
        verify(springAiTextService, never()).completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("agent-planner")
        );
    }

    @Test
    void analyzeProductPosterExtractsStructuredFeatures() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"适合数码桌搭的防水 PVC 创意贴纸","productName":"MacBook AI 创意贴纸","material":"PVC","size":"适配笔记本电脑","color":"彩色","style":"AI 科技感、潮流","scenarios":"电脑、水杯、行李箱、手账装饰","targetAudience":"学生、设计师、程序员、AI 爱好者","sellingPoints":"防水耐磨、高清彩印、可移除不留胶","platformStyle":"小红书种草"}
                """);

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("PVC 防水耐磨 MacBook AI 创意贴纸")
        );

        assertThat(response.productName()).isEqualTo("MacBook AI 创意贴纸");
        assertThat(response.industry()).isEqualTo("文创/贴纸");
        assertThat(response.material()).isEqualTo("PVC");
        assertThat(response.platformStyle()).isEqualTo("小红书种草");
        assertThat(response.sellingPoints()).contains("防水耐磨", "高清彩印");
        assertThat(response.readyToGenerate()).isTrue();
        assertThat(response.missingInformation()).isEmpty();
        assertThat(response.assistantMessage()).isEqualTo("我已经把当前能确定的产品属性整理成表格了，现在可以开始生成商品详情图。");
        verify(springAiTextService).completeText(eq(config), anyString(), anyString(), any(), eq("product-poster-analysis"));
        verify(springAiTextService, never()).completeText(eq(config), anyString(), anyString(), any(), eq("product-poster-ready-message"));
    }

    @Test
    void analyzeProductPosterUsesStrictFactGuard() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"PVC贴纸","productName":"AI 贴纸","industry":"文创/贴纸","material":"PVC","size":"未明确","color":"彩色","style":"科技感","scenarios":"电脑桌搭","targetAudience":"程序员","sellingPoints":"防水耐磨","extraDetails":"","platformStyle":"通用商业"}
                """);

        service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("PVC 防水贴纸")
        );

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), systemPromptCaptor.capture(), anyString(), any(), eq("product-poster-analysis"));
        assertThat(systemPromptCaptor.getValue())
                .contains("只能使用用户描述里明确给出的信息")
                .contains("绝不能脑补、补全或杜撰");
    }

    @Test
    void analyzeProductPosterAsksPerfumeFollowUpWhenNotesAreMissing() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一款偏礼赠场景的香水","productName":"香水","industry":"香水/香氛","material":"玻璃","size":"50ml","color":"透明","style":"高级极简","scenarios":"送礼","targetAudience":"女性","sellingPoints":"高级香气、礼赠氛围","extraDetails":"","platformStyle":"小红书种草"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("为了把这张香水详情图的气质做准，你告诉我这款香水的前调、中调、后调分别是什么？");

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("香水")
        );

        assertThat(response.industry()).isEqualTo("香水/香氛");
        assertThat(response.readyToGenerate()).isFalse();
        assertThat(response.missingInformation()).contains("前调 / 中调 / 后调");
        assertThat(response.nextQuestion()).isEqualTo("为了把这张香水详情图的气质做准，你告诉我这款香水的前调、中调、后调分别是什么？");
        assertThat(response.assistantMessage()).isEqualTo("我已经先把当前能确定的产品属性整理成表格了，为了把这张香水详情图的气质做准，你告诉我这款香水的前调、中调、后调分别是什么？");
        assertThat(response.assistantMessage()).doesNotContain("已完成分析", "目前还缺少", "还请补充", "物理属性");
    }

    @Test
    void analyzeProductPosterMarksReadyWhenPerfumeNotesAreComplete() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"适合送礼的花果木质调香水","productName":"木质花香香水","industry":"香水/香氛","material":"玻璃","size":"50ml","color":"透明","style":"高级极简","scenarios":"约会、送礼、通勤","targetAudience":"25-35 岁女性","sellingPoints":"花果木质调、持香自然、礼赠高级感","extraDetails":"前调：佛手柑、梨；中调：玫瑰、茉莉；后调：雪松、麝香；香型：花果木质调","platformStyle":"小红书种草"}
                """);

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("香水，前调佛手柑和梨，中调玫瑰茉莉，后调雪松麝香，适合送礼和通勤")
        );

        assertThat(response.industry()).isEqualTo("香水/香氛");
        assertThat(response.readyToGenerate()).isTrue();
        assertThat(response.missingInformation()).isEmpty();
        assertThat(response.assistantMessage()).isEqualTo("我已经把当前能确定的产品属性整理成表格了，现在可以开始生成商品详情图。");
    }

    @Test
    void analyzeProductPosterKeepsExplicitDetailDesignStyleWithoutWebSearch() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        ProductIndustryResearchService productIndustryResearchService = mock(ProductIndustryResearchService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                productIndustryResearchService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"适合数码桌搭的高品质 AI 贴纸","productName":"AI 创意贴纸","industry":"文创/贴纸","material":"PVC不干胶","size":"13 寸电脑适配","color":"彩色","style":"科技极简","detailDesignStyle":"杂志风留白排版","scenarios":"笔记本电脑桌搭","targetAudience":"程序员、AI 爱好者","sellingPoints":"防水耐磨、高清彩印","extraDetails":"单张尺寸 6cm，12 张/套","platformStyle":"通用商业"}
                """);

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("一套适合程序员桌搭的 AI 创意贴纸，PVC不干胶，单张 6cm，12 张/套，防水耐磨，详情图设计风格走杂志风留白排版。")
        );

        assertThat(response.detailDesignStyle()).isEqualTo("杂志风留白排版");
        assertThat(response.assistantMessage()).contains("并确认了详情图设计风格：杂志风留白排版。");
        verify(productIndustryResearchService, never()).research(any(), anyString());
    }

    @Test
    void analyzeProductPosterAutofillsDetailDesignStyleFromWebSearchWhenMissing() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        ProductIndustryResearchService productIndustryResearchService = mock(ProductIndustryResearchService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                productIndustryResearchService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"适合程序员桌搭的 AI 创意贴纸","productName":"AI 创意贴纸","industry":"文创/贴纸","material":"PVC不干胶","size":"单张 6cm，12 张/套","color":"彩色","style":"科技极简","scenarios":"笔记本电脑桌搭","targetAudience":"程序员、AI 爱好者","sellingPoints":"防水耐磨、高清彩印","extraDetails":"","platformStyle":"通用商业"}
                """);
        when(productIndustryResearchService.research(eq(config), anyString())).thenReturn(Optional.of(
                new ProductIndustryResearchService.IndustryResearchResult(
                        "行业/品类：文创/贴纸；行业视觉风格：科技编辑感、低饱和高级留白；流行趋势风格：杂志式桌搭展示；详情页版式：模块化卖点分栏。",
                        List.of()
                )
        ));

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("PVC 防水 AI 创意贴纸，适合程序员桌搭，单张 6cm，12 张/套。")
        );

        assertThat(response.readyToGenerate()).isTrue();
        assertThat(response.detailDesignStyle()).isEqualTo("科技编辑感、低饱和高级留白；杂志式桌搭展示；模块化卖点分栏。");
        assertThat(response.assistantMessage()).contains("并通过 WebSearch 补全了详情图设计风格");
    }

    @Test
    void createProductPosterPlanPassesDetailDesignStyleToPlanner() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        ProductIndustryResearchService productIndustryResearchService = mock(ProductIndustryResearchService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                productIndustryResearchService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-planner")
        )).thenReturn("""
                [{"title":"首屏主图","goal":"突出产品主视觉","prompt":"为 AI 创意贴纸生成首屏主图。","aspectRatio":"3:4","cardType":"hero","productName":"AI 创意贴纸","focus":"主视觉","mustShow":["主体"],"avoid":["杜撰参数"],"composition":"主体居中","layout":"杂志风留白排版","copyDirection":"少字准字","tone":"简洁优雅","factGuard":"只用已知事实"}]
                """);
        when(productIndustryResearchService.research(eq(config), anyString(), any())).thenReturn(Optional.empty());

        AiProxyDtos.ProductPosterRequest request = new AiProxyDtos.ProductPosterRequest(
                List.of("img-1"),
                "img-1",
                "PVC 防水 AI 创意贴纸，单张 6cm，12 张/套。",
                "AI 创意贴纸",
                "文创/贴纸",
                "PVC不干胶",
                "单张 6cm，12 张/套",
                "彩色",
                "科技极简",
                "程序员桌搭",
                "程序员、AI 爱好者",
                "防水耐磨、高清彩印",
                "",
                "通用商业",
                1,
                "single",
                "用户：希望整体更简洁。\n助手：建议走杂志风。",
                "杂志风留白排版"
        );

        service.createProductPosterPlan(
                userId,
                request,
                List.of(new AiProxyDtos.ImageReferenceCandidate("img-1", "主产品图", 1, 800, 800)),
                "3:4"
        );

        ArgumentCaptor<String> plannerInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), anyString(), plannerInputCaptor.capture(), any(), eq("product-poster-planner"));
        assertThat(plannerInputCaptor.getValue())
                .contains("详情图设计风格：杂志风留白排版")
                .contains("商品详情图对话上下文：用户：希望整体更简洁。");
    }

    @Test
    void analyzeProductPosterUsesVisionFactsAndKeepsSuggestionsForConfirmation() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        ProductPosterVisionAnalysisService visionAnalysisService = mock(ProductPosterVisionAnalysisService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                null,
                visionAnalysisService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        AiProxyDtos.ImageReferenceCandidate image = new AiProxyDtos.ImageReferenceCandidate(
                "img-1",
                "产品图",
                1,
                800,
                800,
                "33333333-3333-3333-3333-333333333333"
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(visionAnalysisService.analyze(eq(userId), eq(config), any(), eq("我也不清楚，你先帮我看图整理"))).thenReturn(Optional.of(
                new ProductPosterVisionAnalysisService.VisionAnalysisResult(
                        "一张科技风贴纸产品图",
                        List.of(
                                new AiProxyDtos.ProductPosterFact("productName", "产品名称", "AI 创意贴纸", "ocr", "medium", "包装文字可见"),
                                new AiProxyDtos.ProductPosterFact("industry", "行业", "文创/贴纸", "image", "high", "图案题材明确"),
                                new AiProxyDtos.ProductPosterFact("color", "颜色 / 外观", "彩色科技风图案", "image", "high", "画面直接可见")
                        ),
                        List.of(
                                new AiProxyDtos.ProductPosterFact("targetAudience", "使用人群", "程序员、AI 爱好者", "inference", "medium", "题材和使用场景偏技术圈层")
                        )
                )
        ));
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一款科技感贴纸产品","productName":"","industry":"","material":"","size":"","color":"","style":"科技潮流","scenarios":"","targetAudience":"","sellingPoints":"","extraDetails":"","platformStyle":"通用商业"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("我先从图里帮你看出这是偏科技圈层的贴纸产品；如果你也不清楚具体规格，可以先确认一下它是不是卖给程序员和 AI 爱好者，或者补一张包装参数图给我。");

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("我也不清楚，你先帮我看图整理", List.of(image))
        );

        assertThat(response.productName()).isEqualTo("AI 创意贴纸");
        assertThat(response.industry()).isEqualTo("文创/贴纸");
        assertThat(response.color()).isEqualTo("彩色科技风图案");
        assertThat(response.readyToGenerate()).isFalse();
        assertThat(response.confirmedFacts()).hasSize(3);
        assertThat(response.suggestedFacts()).hasSize(1);
        assertThat(response.suggestedFacts().get(0).value()).isEqualTo("程序员、AI 爱好者");
        assertThat(response.assistantMessage()).contains("先确认一下它是不是卖给程序员和 AI 爱好者");

        ArgumentCaptor<String> analysisInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), anyString(), analysisInputCaptor.capture(), any(), eq("product-poster-analysis"));
        assertThat(analysisInputCaptor.getValue())
                .contains("产品图直读事实")
                .contains("AI 创意贴纸")
                .contains("产品图候选建议")
                .contains("程序员、AI 爱好者");
    }

    @Test
    void analyzeProductPosterAllowsAutonomousPlanningWhenUserDelegatesDesignerRole() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        ProductIndustryResearchService productIndustryResearchService = mock(ProductIndustryResearchService.class);
        ProductPosterVisionAnalysisService visionAnalysisService = mock(ProductPosterVisionAnalysisService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                productIndustryResearchService,
                visionAnalysisService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        String prompt = "我上传了 ck 内衣，我对这套内衣的属性一无所知，你像顶级内衣品牌设计师一样全权帮我规划，能用 websearch 就用。";
        AiProxyDtos.ImageReferenceCandidate image = new AiProxyDtos.ImageReferenceCandidate(
                "img-1",
                "CK 内衣",
                1,
                1024,
                1024,
                "44444444-4444-4444-4444-444444444444"
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(visionAnalysisService.analyze(eq(userId), eq(config), any(), eq(prompt))).thenReturn(Optional.of(
                new ProductPosterVisionAnalysisService.VisionAnalysisResult(
                        "一张 Calvin Klein 风格女士内衣产品图",
                        List.of(
                                new AiProxyDtos.ProductPosterFact("productName", "产品名称", "CK 女士内衣", "ocr", "medium", "logo 可辨识"),
                                new AiProxyDtos.ProductPosterFact("color", "颜色 / 外观", "黑白极简配色", "image", "high", "画面直接可见")
                        ),
                        List.of(
                                new AiProxyDtos.ProductPosterFact("targetAudience", "使用人群", "都市女性", "inference", "medium", "品牌气质与款式判断"),
                                new AiProxyDtos.ProductPosterFact("style", "款式 / 风格", "极简都会感", "inference", "medium", "画面风格判断")
                        )
                )
        ));
        when(productIndustryResearchService.research(eq(config), anyString())).thenReturn(Optional.of(
                new ProductIndustryResearchService.IndustryResearchResult(
                        "行业/品类：高端女士内衣；目标人群：都市女性；行业视觉风格：极简、克制、干净；详情页版式：品牌 editorial 杂志感；产品特点展示：版型、贴合感、面料触感；购买欲钩子：舒适包裹、轻盈贴肤、日常高级感。",
                        List.of()
                )
        ));
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一套极简都会风女士内衣","productName":"","industry":"","material":"","size":"","color":"","style":"高级极简、都会感、干净克制","scenarios":"通勤内搭、居家舒适穿着、日常衣橱基础款","targetAudience":"追求舒适与高级感的都市女性","sellingPoints":"简洁高级、贴合曲线、日常百搭、舒适包裹","extraDetails":"","platformStyle":"通用商业"}
                """);

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest(prompt, List.of(image))
        );

        assertThat(response.industry()).isEqualTo("服饰/鞋包");
        assertThat(response.productName()).isEqualTo("CK 女士内衣");
        assertThat(response.material()).isBlank();
        assertThat(response.size()).isBlank();
        assertThat(response.readyToGenerate()).isTrue();
        assertThat(response.missingInformation()).isEmpty();
        assertThat(response.detailDesignStyle()).isEqualTo("极简、克制、干净；品牌 editorial 杂志感");
        assertThat(response.assistantMessage()).contains("通过 WebSearch 补全了详情图设计风格");

        ArgumentCaptor<String> analysisInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), anyString(), analysisInputCaptor.capture(), any(), eq("product-poster-analysis"));
        assertThat(analysisInputCaptor.getValue())
                .contains("设计师代策划模式")
                .contains("AI websearch 行业调研")
                .contains("高端女士内衣");
        verify(springAiTextService, never()).completeText(eq(config), anyString(), anyString(), any(), eq("product-poster-followup"));
    }

    @Test
    void analyzeProductPosterInfersSchemaFromAbstractIndustrySignals() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一款贴身衣物产品","productName":"轻感贴肤系列","industry":"","material":"","size":"","color":"黑色","style":"极简都会感","scenarios":"日常通勤、长时间穿着","targetAudience":"都市女性","sellingPoints":"贴肤舒适、包裹稳定、剪裁利落","extraDetails":"面料柔软、肩带稳定、穿着包裹感自然","platformStyle":"通用商业"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("我已经抓到这类贴身服饰的方向了；如果你愿意，再补一个材质或规格会让详情图更像真实商品页。");

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("这类产品主打贴肤、包裹、肩带稳定和日常长时间穿着，整体要极简都会感。")
        );

        assertThat(response.industry()).isEqualTo("服饰/鞋包");
        assertThat(response.readyToGenerate()).isFalse();
        assertThat(response.nextQuestion()).contains("材质或规格");
    }

    @Test
    void analyzeProductPosterUsesCommonParentSlotsForPhysicalGoods() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一款高端科技风桌搭产品","productName":"AI 桌搭摆件","industry":"通用商品","material":"金属","size":"未明确","color":"银色","style":"科技极简","scenarios":"工位桌搭","targetAudience":"程序员","sellingPoints":"科技感强、质感高级","extraDetails":"","platformStyle":"通用商业"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("为了让我把比例和参数图做得更可信，这个摆件的具体尺寸或套装规格是多少？");

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("一个适合程序员工位桌搭的 AI 产品摆件，金属材质，科技极简风")
        );

        assertThat(response.readyToGenerate()).isFalse();
        assertThat(response.missingInformation()).contains("尺寸 / 规格 / 容量");
        assertThat(response.nextQuestion()).isEqualTo("为了让我把比例和参数图做得更可信，这个摆件的具体尺寸或套装规格是多少？");
        assertThat(response.assistantMessage()).isEqualTo("我已经先把当前能确定的产品属性整理成表格了，为了让我把比例和参数图做得更可信，这个摆件的具体尺寸或套装规格是多少？");
        assertThat(response.assistantMessage()).doesNotContain("已完成分析", "目前还缺少", "还请补充", "物理属性");
    }

    @Test
    void analyzeProductPosterKeepsAiStickerAsStickerAndAsksLikeDesigner() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"适合AI工具爱好者和科技桌搭场景的高品质PVC防水不干胶贴纸","productName":"AI 工具 LOGO 贴纸","industry":"家居/日用","material":"PVC不干胶","size":"未明确","color":"彩色","style":"科技潮酷","scenarios":"科技桌搭、笔记本电脑装饰","targetAudience":"AI工具爱好者、程序员、大学生","sellingPoints":"防水耐撕、户外耐用、粘性强、撕下不留胶","extraDetails":"","platformStyle":"淘宝/天猫"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("我会按 AI/科技桌搭贴纸的方向来设计，你再告诉我这套贴纸的具体规格吧，比如单张尺寸、张数，或者是图标款、图文款还是套装组合？");

        AiProxyDtos.ProductPosterAnalysisResponse response = service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("一款适合AI工具爱好者和科技桌搭场景的高品质PVC防水不干胶贴纸")
        );

        assertThat(response.industry()).isEqualTo("文创/贴纸");
        assertThat(response.readyToGenerate()).isFalse();
        assertThat(response.nextQuestion()).isEqualTo("我会按 AI/科技桌搭贴纸的方向来设计，你再告诉我这套贴纸的具体规格吧，比如单张尺寸、张数，或者是图标款、图文款还是套装组合？");
        assertThat(response.assistantMessage()).isEqualTo("我已经先把当前能确定的产品属性整理成表格了，我会按 AI/科技桌搭贴纸的方向来设计，你再告诉我这套贴纸的具体规格吧，比如单张尺寸、张数，或者是图标款、图文款还是套装组合？");
        assertThat(response.assistantMessage()).doesNotContain("已完成分析", "识别行业", "目前还缺少", "还请补充", "物理属性");
    }

    @Test
    void analyzeProductPosterUsesAiRoleToGenerateFollowUpQuestion() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-analysis")
        )).thenReturn("""
                {"summary":"一款高品质PVC防水贴纸","productName":"AI 贴纸","industry":"文创/贴纸","material":"PVC不干胶","size":"未明确","color":"彩色","style":"科技潮流","scenarios":"笔记本电脑装饰","targetAudience":"程序员","sellingPoints":"防水耐磨、高清彩印","extraDetails":"","platformStyle":"通用商业"}
                """);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-followup")
        )).thenReturn("为了把这套贴纸的参数图和购买信息做准确，你再告诉我具体规格吧，比如单张尺寸、张数或套装组合。");

        service.analyzeProductPoster(
                userId,
                new AiProxyDtos.ProductPosterAnalysisRequest("一套高品质 PVC 防水贴纸")
        );

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), systemPromptCaptor.capture(), anyString(), any(), eq("product-poster-followup"));
        assertThat(systemPromptCaptor.getValue())
                .contains("你是一位专业的电商产品设计师和商品详情页视觉策划")
                .contains("只问“下一句最有价值的问题”")
                .contains("像真正的设计师一样");
    }

    @Test
    void createProductPosterPlanIncludesConversationContextInPlannerInput() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("product"),
                "product",
                "PVC 防水耐磨贴纸",
                "AI 创意贴纸",
                "",
                "PVC",
                "13 寸",
                "彩色",
                "科技潮流",
                "电脑桌搭",
                "程序员",
                "防水耐磨",
                "",
                "小红书种草",
                1,
                "single",
                "用户：这套贴纸主要卖给程序员和 AI 爱好者。\n助手：好的，我会突出桌搭氛围和科技感。",
                ""
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-planner")
        )).thenReturn("""
                {"posters":[{"title":"首屏图","purpose":"突出产品","prompt":"生成首屏商品详情图","aspectRatio":"3:4"}]}
                """);

        service.createProductPosterPlan(
                userId,
                productPoster,
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "产品图", 1, 1024, 1024, "33333333-3333-3333-3333-333333333333")),
                "3:4"
        );

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(springAiTextService).completeText(eq(config), anyString(), inputCaptor.capture(), any(), eq("product-poster-planner"));
        assertThat(inputCaptor.getValue())
                .contains("产品事实锚点")
                .contains("卡片路由建议")
                .contains("创意与版式约束")
                .contains("商品详情图对话上下文")
                .contains("用户：这套贴纸主要卖给程序员和 AI 爱好者。")
                .contains("助手：好的，我会突出桌搭氛围和科技感。")
                .contains("真实比例要求")
                .contains("视觉比例自然可信")
                .contains("禁止自行脑补、补全或杜撰任何具体数值")
                .contains("编辑感极简")
                .contains("构图规则")
                .contains("一张详情图只讲一个重点");
        assertThat(inputCaptor.getValue()).doesNotContain("行业常见真实尺寸保守推断");
    }

    @Test
    void createProductPosterPlanParsesStructuredCardFields() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("product"),
                "product",
                "PVC 防水耐磨贴纸",
                "AI 创意贴纸",
                "",
                "PVC",
                "13 寸",
                "彩色",
                "科技潮流",
                "电脑桌搭",
                "程序员",
                "防水耐磨",
                "",
                "小红书种草",
                1
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("product-poster-planner")
        )).thenReturn("""
                {
                  "posters":[
                    {
                      "title":"首屏图",
                      "purpose":"突出产品第一眼吸引力",
                      "prompt":"强调产品主体和桌搭氛围",
                      "cardType":"hero",
                      "headline":"AI 桌搭贴纸",
                      "subheading":"防水耐磨，适合程序员工位",
                      "sellingBullets":["防水耐磨","高清彩印","撕下不留胶"],
                      "tags":["PVC","桌搭","程序员"],
                      "sceneFocus":"MacBook 工位桌搭场景",
                      "layoutIntent":"杂志式首屏，标题在左上，产品居中偏右",
                      "compositionDirectives":"大面积留白，产品清晰可售卖",
                      "copyTone":"简洁、高级、科技感",
                      "avoid":"不要廉价促销风",
                      "aspectRatio":"3:4"
                    }
                  ]
                }
                """);

        List<AgentPlannerService.ProductPosterPlanItem> items = service.createProductPosterPlan(
                userId,
                productPoster,
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "产品图", 1, 1024, 1024, "33333333-3333-3333-3333-333333333333")),
                "3:4"
        );

        assertThat(items).hasSize(1);
        AgentPlannerService.ProductPosterPlanItem item = items.get(0);
        assertThat(item.title()).isEqualTo("首屏图");
        assertThat(item.cardType()).isEqualTo("hero");
        assertThat(item.headline()).isEqualTo("AI 桌搭贴纸");
        assertThat(item.subheading()).isEqualTo("防水耐磨，适合程序员工位");
        assertThat(item.sellingBullets()).containsExactly("防水耐磨", "高清彩印", "撕下不留胶");
        assertThat(item.tags()).containsExactly("PVC", "桌搭", "程序员");
        assertThat(item.sceneFocus()).isEqualTo("MacBook 工位桌搭场景");
        assertThat(item.layoutIntent()).contains("标题在左上");
        assertThat(item.compositionDirectives()).contains("大面积留白");
        assertThat(item.copyTone()).isEqualTo("简洁、高级、科技感");
        assertThat(item.avoid()).isEqualTo("不要廉价促销风");
    }

    @Test
    void createPlanThrowsWhenAiIntentClassifierFails() {
        UserApiConfigService userApiConfigService = mock(UserApiConfigService.class);
        SpringAiTextService springAiTextService = mock(SpringAiTextService.class);
        KnowledgeAnswerService knowledgeAnswerService = mock(KnowledgeAnswerService.class);
        AgentPlannerService service = new AgentPlannerService(
                userApiConfigService,
                springAiTextService,
                knowledgeAnswerService,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        UUID userId = UUID.randomUUID();
        UserApiConfigDtos.ResolvedConfig config = new UserApiConfigDtos.ResolvedConfig(
                "https://api.sisct2.xyz/v1",
                "test-key",
                "gpt-image-2",
                "gpt-5.4-mini",
                "https://api.sisct2.xyz/v1/images/generations",
                "https://api.sisct2.xyz/v1/images/edits",
                false
        );
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "你是谁",
                "",
                "auto",
                "",
                "",
                List.of()
        );

        when(userApiConfigService.getRequiredConfig(userId)).thenReturn(config);
        when(springAiTextService.completeText(
                eq(config),
                anyString(),
                anyString(),
                any(),
                eq("agent-intent-classifier")
        )).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_UPSTREAM_ERROR", "Spring AI 上游错误"));

        assertThatThrownBy(() -> service.createPlan(userId, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Spring AI 上游错误");
    }

    @Test
    void createForcedToolPlanUsesContextImageAsEditBaseWithoutAiPlanning() {
        AgentPlannerService service = new AgentPlannerService(null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "去背景",
                "main-image",
                "auto",
                "",
                "",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("main-image", "人物照片", 1, 768, 1024, "11111111-1111-1111-1111-111111111111"),
                        new AiProxyDtos.ImageReferenceCandidate("ref-image", "参考背景", 2, 1024, 768, "22222222-2222-2222-2222-222222222222")
                )
        );

        AiProxyDtos.AgentPlanResponse plan = service.createForcedToolPlan(request, "tool.image.remove-background");

        assertThat(plan.responseMode()).isEqualTo("execute");
        assertThat(plan.taskType()).isEqualTo("image-edit");
        assertThat(plan.mode()).isEqualTo("background-removal");
        assertThat(plan.baseImageId()).isEqualTo("main-image");
        assertThat(plan.referenceImageIds()).containsExactly("ref-image");
        assertThat(plan.source()).isEqualTo("tool");
    }

    @Test
    void createForcedToolPlanMapsLocalRedrawToImageEditTool() {
        AgentPlannerService service = new AgentPlannerService(null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "把圈里的花改成小猫",
                "base",
                "auto",
                "",
                "local-redraw",
                List.of(new AiProxyDtos.ImageReferenceCandidate("base", "花园照片", 1, 1024, 1024, "33333333-3333-3333-3333-333333333333"))
        );

        AiProxyDtos.AgentPlanResponse plan = service.createForcedToolPlan(request, "tool.image.local-redraw");

        assertThat(plan.taskType()).isEqualTo("image-edit");
        assertThat(plan.mode()).isEqualTo("edit");
        assertThat(plan.baseImageId()).isEqualTo("base");
        assertThat(plan.steps()).extracting(AiProxyDtos.AgentPlanStep::id)
                .containsExactly("identify-images", "optimize-edit-prompt", "run-image-edit");
    }

    @Test
    void createForcedToolPlanMapsPanoramaToPanoramaMode() {
        AgentPlannerService service = new AgentPlannerService(null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "把这张图转成360全景",
                "base",
                "2:1",
                "2k",
                "panorama",
                List.of(new AiProxyDtos.ImageReferenceCandidate("base", "室内照片", 1, 1024, 512, "44444444-4444-4444-4444-444444444444"))
        );

        AiProxyDtos.AgentPlanResponse plan = service.createForcedToolPlan(request, "tool.image.panorama");

        assertThat(plan.taskType()).isEqualTo("image-edit");
        assertThat(plan.mode()).isEqualTo("panorama");
        assertThat(plan.aspectRatio()).isEqualTo("2:1");
        assertThat(plan.baseImageId()).isEqualTo("base");
        assertThat(plan.steps()).extracting(AiProxyDtos.AgentPlanStep::id)
                .containsExactly("identify-panorama", "optimize-panorama", "run-panorama");
    }

    @Test
    void productPosterPlanIncludesWebSearchResearchStep() {
        AgentPlannerService service = new AgentPlannerService(null, null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("product"),
                "product",
                "PVC 防水耐磨 MacBook AI 创意贴纸",
                "MacBook AI 创意贴纸",
                "PVC",
                "13 寸",
                "彩色",
                "潮流科技",
                "电脑桌搭",
                "AI 爱好者",
                "防水耐磨、高清彩印",
                "小红书种草",
                3
        );
        AiProxyDtos.AgentPlanRequest request = new AiProxyDtos.AgentPlanRequest(
                "生成商品详情图",
                "product",
                "3:4",
                "2k",
                "product-poster",
                productPoster,
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "产品图", 1, 1024, 1024, "33333333-3333-3333-3333-333333333333"))
        );

        AiProxyDtos.AgentPlanResponse plan = service.createForcedToolPlan(request, "tool.product.poster");

        assertThat(plan.steps()).extracting(AiProxyDtos.AgentPlanStep::id)
                .containsExactly("analyze-product", "research-industry", "plan-poster-set", "run-product-posters");
    }
}
