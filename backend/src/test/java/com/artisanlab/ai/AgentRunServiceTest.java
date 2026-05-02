package com.artisanlab.ai;

import com.artisanlab.skill.ExternalSkillService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {
    @Test
    void answerPlanDoesNotCreateImageJob() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "livart 怎么导出图片？",
                "",
                "auto",
                "",
                "",
                List.of(),
                "",
                "",
                "",
                "run-answer"
        );
        AiProxyDtos.AgentPlanResponse plan = new AiProxyDtos.AgentPlanResponse(
                true,
                "answer",
                "",
                "可以在下载按钮里导出图片。",
                "text-to-image",
                "generate",
                0,
                "",
                List.of(),
                "auto",
                "可以在下载按钮里导出图片。",
                "",
                "可以在下载按钮里导出图片。",
                List.of(),
                List.of(),
                "ai"
        );

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        assertThat(response.responseMode()).isEqualTo("answer");
        assertThat(response.jobs()).isEmpty();
        assertThat(response.displayMessage()).isEqualTo("可以在下载按钮里导出图片。");
        verify(eventBroadcaster, times(3)).publishAgentRunEvent(eq(userId), eq("run-answer"), any());
        verify(aiProxyService, never()).createTextToImageJobFromAgent(any(), any(), any());
        verify(aiProxyService, never()).createImageEditJobFromAgent(any(), any());
    }

    @Test
    void textToImagePlanCreatesRequestedImagesInOneBatch() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成3张小猫",
                "",
                "1:1",
                "4k",
                "",
                List.of(),
                "",
                "",
                "",
                "run-generate"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("text-to-image", "generate", 3, "", List.of(), "1:1");

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);
        when(aiProxyService.createTextToImageJobsFromAgent(eq(userId), eq("生成3张小猫"), eq("1:1"), eq("4k"), eq(3), eq("")))
                .thenReturn(List.of(job("job-1"), job("job-2"), job("job-3")));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId)
                .containsExactly("job-1", "job-2", "job-3");
        assertThat(response.displayTitle()).isEqualTo("小猫图片");
        assertThat(response.displayMessage()).isEqualTo("我开始为你生成 3 张小猫图片。");
        verify(aiProxyService).createTextToImageJobsFromAgent(eq(userId), eq("生成3张小猫"), eq("1:1"), eq("4k"), eq(3), eq(""));
        verify(aiProxyService, never()).createTextToImageJobFromAgent(any(), any(), any());
        verify(eventBroadcaster, times(5)).publishAgentRunEvent(eq(userId), eq("run-generate"), any());
    }

    @Test
    void selectedExternalSkillAddsGuidanceToTextToImagePromptOptimization() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(eq("gpt-image"))).thenReturn("外部 Skill：GPT Image 2\nSkill 指南：按 Skill 编译提示词");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成一张中文海报",
                "",
                "3:4",
                "2k",
                "",
                List.of(),
                "",
                "",
                "gpt-image",
                "run-skill-generate"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("text-to-image", "generate", 1, "", List.of(), "3:4");

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);
        when(aiProxyService.createTextToImageJobsFromAgent(
                eq(userId),
                eq("生成一张中文海报"),
                eq("3:4"),
                eq("2k"),
                eq(1),
                eq("外部 Skill：GPT Image 2\nSkill 指南：按 Skill 编译提示词")
        )).thenReturn(List.of(job("skill-job")));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId).containsExactly("skill-job");
        verify(externalSkillService).requirePromptGuidance(eq("gpt-image"));
        verify(aiProxyService).createTextToImageJobsFromAgent(
                eq(userId),
                eq("生成一张中文海报"),
                eq("3:4"),
                eq("2k"),
                eq(1),
                eq("外部 Skill：GPT Image 2\nSkill 指南：按 Skill 编译提示词")
        );
    }

    @Test
    void imageEditPlanCreatesBackendEditJobWithAssetIdsAndRolePrompt() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID personAssetId = UUID.randomUUID();
        UUID shoeAssetId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "把@shoe 放在@person 的脚上",
                "person",
                "auto",
                "",
                "",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("person", "人物图", 1, 512, 768, personAssetId.toString()),
                        new AiProxyDtos.ImageReferenceCandidate("shoe", "红色鞋子", 2, 300, 300, shoeAssetId.toString())
                ),
                "",
                "",
                "",
                "run-edit"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "edit", 1, "person", List.of("shoe"), "auto");

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("edit-job"));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        AiProxyService.AgentImageEditJobRequest jobRequest = captor.getValue();
        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId).containsExactly("edit-job");
        assertThat(jobRequest.imageAssetId()).isEqualTo(personAssetId);
        assertThat(jobRequest.referenceAssetIds()).containsExactly(shoeAssetId);
        assertThat(jobRequest.prompt()).contains("原图“人物图”", "参考图 1“红色鞋子”");
        assertThat(jobRequest.prompt()).contains("原图宽高 512:768", "约 2:3");
        assertThat(jobRequest.prompt()).doesNotContain("@person", "@shoe");
    }

    @Test
    void selectedExternalSkillSwitchesGenericImageEditToSkillMode() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(eq("gpt-image"))).thenReturn("外部 Skill：GPT Image 2\nSkill 指南：保留参考图一致性");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID baseAssetId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "把画面改成水彩风格",
                "base",
                "auto",
                "",
                "",
                List.of(new AiProxyDtos.ImageReferenceCandidate("base", "风景图", 1, 1024, 768, baseAssetId.toString())),
                "",
                "",
                "gpt-image",
                "run-skill-edit"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "edit", 1, "base", List.of(), "auto");

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("skill-edit-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        AiProxyService.AgentImageEditJobRequest jobRequest = captor.getValue();
        assertThat(jobRequest.promptOptimizationMode()).isEqualTo("skill-image-to-image");
        assertThat(jobRequest.imageContext()).contains("外部 Skill：GPT Image 2", "保留参考图一致性");
    }

    @Test
    void forcedToolRunBypassesAiPlannerAndCreatesImageEditJob() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "去背景",
                "base",
                "auto",
                "",
                "",
                List.of(new AiProxyDtos.ImageReferenceCandidate("base", "人物图", 1, 512, 768, assetId.toString())),
                "",
                "tool.image.remove-background",
                "",
                "run-tool"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "background-removal", 1, "base", List.of(), "auto");

        when(plannerService.createForcedToolPlan(any(), eq("tool.image.remove-background"))).thenReturn(plan);
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("tool-job"));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId).containsExactly("tool-job");
        verify(plannerService, never()).createPlan(any(), any());
        verify(plannerService).createForcedToolPlan(any(), eq("tool.image.remove-background"));
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), any());
    }

    @Test
    void productPosterUsesMultipleSelectedProductImagesAsSameProductReferences() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID frontAssetId = UUID.randomUUID();
        UUID detailAssetId = UUID.randomUUID();
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("front", "detail"),
                "front",
                "PVC 防水耐磨 MacBook AI 创意贴纸",
                "MacBook 贴纸",
                "PVC",
                "13 寸",
                "彩色",
                "潮流",
                "电脑桌搭",
                "数码爱好者",
                "防水耐磨",
                "小红书种草",
                2
        );
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成2张商品详情图",
                "front",
                "3:4",
                "2k",
                "product-poster",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("front", "产品正面图", 1, 1024, 1024, frontAssetId.toString()),
                        new AiProxyDtos.ImageReferenceCandidate("detail", "产品细节图", 2, 800, 600, detailAssetId.toString())
                ),
                "",
                "tool.product.poster",
                "",
                productPoster,
                "run-product-poster"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "product-poster", 2, "front", List.of("detail"), "3:4");

        when(plannerService.createForcedToolPlan(any(), eq("tool.product.poster"))).thenReturn(plan);
        when(plannerService.createProductPosterPlan(eq(userId), eq(productPoster), any(), eq("3:4"), any()))
                .thenReturn(List.of(
                        new AgentPlannerService.ProductPosterPlanItem("首屏详情图", "突出产品", "生成首屏详情图，加入中文短标题和卖点文字", "3:4"),
                        new AgentPlannerService.ProductPosterPlanItem("场景说明图", "展示场景", "生成场景说明图，加入适用场景文字", "3:4")
                ));
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any()))
                .thenReturn(job("poster-job-1"))
                .thenReturn(job("poster-job-2"));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService, times(2)).createImageEditJobFromAgent(eq(userId), captor.capture());
        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId)
                .containsExactly("poster-job-1", "poster-job-2");
        for (AiProxyService.AgentImageEditJobRequest jobRequest : captor.getAllValues()) {
            assertThat(jobRequest.imageAssetId()).isEqualTo(frontAssetId);
            assertThat(jobRequest.referenceAssetIds()).containsExactly(detailAssetId);
            assertThat(jobRequest.prompt()).contains("第 1 张 image", "第 2 张 image", "同一个产品", "可读中文文字描述", "每张详情图只聚焦一个主题", "1 到 3 个核心卖点", "禁止自行脑补、补全或杜撰任何具体数值");
            assertThat(jobRequest.imageContext()).contains("多张 image 都属于同一个产品", "产品细节图", "商品详情图", "编辑感极简", "高级留白", "一张详情图只讲一个重点", "禁止自行脑补、补全或杜撰任何具体数值");
            assertThat(jobRequest.prompt()).doesNotContain("行业常见真实尺寸");
            assertThat(jobRequest.imageContext()).doesNotContain("行业常见真实尺寸");
            assertThat(jobRequest.promptOptimizationMode()).isEqualTo("product-poster");
        }
    }

    @Test
    void productPosterDefaultsToImageCraftSkillGuidance() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(eq(""))).thenReturn("");
        when(externalSkillService.requirePromptGuidance(eq("image-craft"))).thenReturn("外部 Skill：Image Craft\nSkill 指南：商品摄影与商品详情图审美");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID productAssetId = UUID.randomUUID();
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("product"),
                "product",
                "香水，玻璃瓶，适合送礼",
                "香水",
                "玻璃",
                "50ml",
                "透明",
                "高级极简",
                "送礼",
                "女性",
                "香氛高级",
                "小红书种草",
                1
        );
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成1张商品详情图",
                "product",
                "3:4",
                "2k",
                "product-poster",
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "香水产品图", 1, 1024, 1024, productAssetId.toString())),
                "",
                "tool.product.poster",
                "",
                productPoster,
                "run-product-poster-skill"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "product-poster", 1, "product", List.of(), "3:4");

        when(plannerService.createForcedToolPlan(any(), eq("tool.product.poster"))).thenReturn(plan);
        when(plannerService.createProductPosterPlan(eq(userId), eq(productPoster), any(), eq("3:4"), any()))
                .thenReturn(List.of(new AgentPlannerService.ProductPosterPlanItem("首屏详情图", "突出产品", "生成香水首屏详情图", "3:4")));
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("poster-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        assertThat(captor.getValue().imageContext()).contains("外部 Skill：Image Craft", "商品详情图审美");
    }

    @Test
    void productPosterSeriesModeKeepsImagesAsSeparateProducts() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID redAssetId = UUID.randomUUID();
        UUID blueAssetId = UUID.randomUUID();
        AiProxyDtos.ProductPosterRequest productPoster = new AiProxyDtos.ProductPosterRequest(
                List.of("red", "blue"),
                "red",
                "同一系列的两款 AI 创意贴纸，分别是红色款和蓝色款",
                "AI 创意贴纸系列",
                "",
                "PVC",
                "13 寸",
                "红色、蓝色",
                "科技潮流",
                "电脑桌搭",
                "AI 爱好者",
                "防水耐磨、两种配色",
                "",
                "小红书种草",
                1,
                "series"
        );
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成系列商品详情图",
                "red",
                "3:4",
                "2k",
                "product-poster",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("red", "红色贴纸款", 1, 1024, 1024, redAssetId.toString()),
                        new AiProxyDtos.ImageReferenceCandidate("blue", "蓝色贴纸款", 2, 1024, 1024, blueAssetId.toString())
                ),
                "",
                "tool.product.poster",
                "",
                productPoster,
                "run-product-poster-series"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "product-poster", 1, "red", List.of("blue"), "3:4");

        when(plannerService.createForcedToolPlan(any(), eq("tool.product.poster"))).thenReturn(plan);
        when(plannerService.createProductPosterPlan(eq(userId), eq(productPoster), any(), eq("3:4"), any()))
                .thenReturn(List.of(new AgentPlannerService.ProductPosterPlanItem("系列展示图", "展示两款系列产品", "生成系列陈列详情图", "3:4")));
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("poster-series-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        assertThat(captor.getValue().prompt())
                .contains("产品系列", "不同产品/SKU", "不要融合成一个商品");
        assertThat(captor.getValue().imageContext())
                .contains("产品系列", "保留每个产品的独立外观和差异", "红色贴纸款", "蓝色贴纸款");
    }

    @Test
    void productPosterConversationContextIsIncludedInGenerationPrompt() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID productAssetId = UUID.randomUUID();
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
                "用户：这套贴纸主要面向程序员桌搭。\n助手：好的，我会强化科技感和工位氛围。",
                ""
        );
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成1张商品详情图",
                "product",
                "3:4",
                "2k",
                "product-poster",
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "贴纸产品图", 1, 1024, 1024, productAssetId.toString())),
                "",
                "tool.product.poster",
                "",
                productPoster,
                "run-product-poster-context"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "product-poster", 1, "product", List.of(), "3:4");

        when(plannerService.createForcedToolPlan(any(), eq("tool.product.poster"))).thenReturn(plan);
        when(plannerService.createProductPosterPlan(eq(userId), eq(productPoster), any(), eq("3:4"), any()))
                .thenReturn(List.of(new AgentPlannerService.ProductPosterPlanItem("首屏详情图", "突出产品", "生成首屏商品详情图", "3:4")));
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("poster-context-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        assertThat(captor.getValue().prompt())
                .contains("商品详情图对话上下文")
                .contains("用户：这套贴纸主要面向程序员桌搭。")
                .contains("助手：好的，我会强化科技感和工位氛围。")
                .contains("视觉比例自然可信")
                .contains("禁止自行脑补、补全或杜撰任何具体数值")
                .contains("每张详情图只聚焦一个主题")
                .contains("1 到 3 个核心卖点");
        assertThat(captor.getValue().imageContext())
                .contains("商品详情图对话上下文")
                .contains("程序员桌搭")
                .contains("科技感和工位氛围")
                .contains("视觉比例自然可信")
                .contains("禁止自行脑补、补全或杜撰任何具体数值")
                .contains("高级留白")
                .contains("一张详情图只讲一个重点");
    }

    @Test
    void productPosterBuildsDeterministicPromptFromStructuredCardPlan() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID productAssetId = UUID.randomUUID();
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
                "防水耐磨、高清彩印、撕下不留胶",
                "",
                "小红书种草",
                1
        );
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成1张商品详情图",
                "product",
                "3:4",
                "2k",
                "product-poster",
                List.of(new AiProxyDtos.ImageReferenceCandidate("product", "贴纸产品图", 1, 1024, 1024, productAssetId.toString())),
                "",
                "tool.product.poster",
                "",
                productPoster,
                "run-product-poster-structured"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "product-poster", 1, "product", List.of(), "3:4");

        when(plannerService.createForcedToolPlan(any(), eq("tool.product.poster"))).thenReturn(plan);
        when(plannerService.createProductPosterPlan(eq(userId), eq(productPoster), any(), eq("3:4"), any()))
                .thenReturn(List.of(new AgentPlannerService.ProductPosterPlanItem(
                        "首屏详情图",
                        "突出产品第一眼吸引力",
                        "强调产品主体和科技桌搭氛围",
                        "3:4",
                        "hero",
                        "AI 桌搭贴纸",
                        "防水耐磨，适合程序员工位",
                        List.of("防水耐磨", "高清彩印", "撕下不留胶"),
                        List.of("PVC", "桌搭", "程序员"),
                        "MacBook 工位桌搭场景",
                        "杂志式首屏，标题在左上，产品居中偏右",
                        "大面积留白，产品绝对主角",
                        "简洁、高级、科技感",
                        "不要廉价促销风"
                )));
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("poster-structured-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        assertThat(captor.getValue().prompt())
                .contains("卡片类型：hero")
                .contains("主标题：AI 桌搭贴纸")
                .contains("副标题：防水耐磨，适合程序员工位")
                .contains("卖点短句：防水耐磨；高清彩印；撕下不留胶")
                .contains("标签词：PVC；桌搭；程序员")
                .contains("场景焦点：MacBook 工位桌搭场景")
                .contains("版式规划：杂志式首屏，标题在左上，产品居中偏右")
                .contains("构图要求：大面积留白，产品绝对主角")
                .contains("文案语气：简洁、高级、科技感")
                .contains("避免事项：不要廉价促销风");
    }

    @Test
    void viewChangePromptKeepsSubjectPoseAndGazeFixedInScene() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        ExternalSkillService externalSkillService = mock(ExternalSkillService.class);
        when(externalSkillService.requirePromptGuidance(any())).thenReturn("");
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster, externalSkillService);
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "相机移动到原图左侧 45 度，从左侧看向固定不动的完整场景",
                "base",
                "auto",
                "",
                "",
                List.of(new AiProxyDtos.ImageReferenceCandidate("base", "人物车内图", 1, 512, 768, assetId.toString())),
                "",
                "tool.image.change-view",
                "",
                "run-view-change"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("image-edit", "view-change", 1, "base", List.of(), "auto");

        when(plannerService.createForcedToolPlan(any(), eq("tool.image.change-view"))).thenReturn(plan);
        when(aiProxyService.createImageEditJobFromAgent(eq(userId), any())).thenReturn(job("view-change-job"));

        service.run(userId, request);

        ArgumentCaptor<AiProxyService.AgentImageEditJobRequest> captor = ArgumentCaptor.forClass(AiProxyService.AgentImageEditJobRequest.class);
        verify(aiProxyService).createImageEditJobFromAgent(eq(userId), captor.capture());
        AiProxyService.AgentImageEditJobRequest jobRequest = captor.getValue();
        assertThat(jobRequest.prompt()).contains(
                "画面中所有可见元素",
                "保持原先的身体姿态、头部朝向和眼神方向",
                "不要让角色重新转头、转身或看向新镜头",
                "不应继续直视当前画面",
                "角色视线仍指向原始相机位置",
                "direct eye contact with the new camera",
                "保持原图镜头焦段",
                "禁止扩大视野",
                "禁止把特写变成近景、中景或远景",
                "相机位置发生变化"
        ).doesNotContain("前景主体");
    }

    private static AiProxyDtos.AgentPlanResponse executePlan(
            String taskType,
            String mode,
            int count,
            String baseImageId,
            List<String> referenceImageIds,
            String aspectRatio
    ) {
        return new AiProxyDtos.AgentPlanResponse(
                true,
                "execute",
                "",
                "",
                taskType,
                mode,
                count,
                baseImageId,
                referenceImageIds,
                aspectRatio,
                "开始执行",
                "小猫图片",
                count > 1 ? "我开始为你生成 %d 张小猫图片。".formatted(count) : "我开始为你生成这张小猫图片。",
                List.of("识别意图", "规划任务", "执行任务"),
                List.of(
                        new AiProxyDtos.AgentPlanStep("analyze", "理解需求", "分析用户意图", "analysis"),
                        new AiProxyDtos.AgentPlanStep("prompt", "整理提示词", "整理执行指令", "prompt"),
                        new AiProxyDtos.AgentPlanStep("run", "执行任务", "调用图片工具", "generate")
                ),
                "ai"
        );
    }

    private static Map<String, Object> job(String jobId) {
        return Map.of(
                "jobId", jobId,
                "status", "queued"
        );
    }
}
