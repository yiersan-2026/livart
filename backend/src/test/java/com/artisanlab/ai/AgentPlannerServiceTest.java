package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.artisanlab.userconfig.UserApiConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
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
}
