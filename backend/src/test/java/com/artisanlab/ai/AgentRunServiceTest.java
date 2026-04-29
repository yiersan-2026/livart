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
