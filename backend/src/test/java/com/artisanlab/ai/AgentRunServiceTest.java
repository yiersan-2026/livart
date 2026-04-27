package com.artisanlab.ai;

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
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster);
        UUID userId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "livart 怎么导出图片？",
                "",
                "auto",
                "",
                List.of(),
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
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster);
        UUID userId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "生成3张小猫",
                "",
                "1:1",
                "",
                List.of(),
                "",
                "run-generate"
        );
        AiProxyDtos.AgentPlanResponse plan = executePlan("text-to-image", "generate", 3, "", List.of(), "1:1");

        when(plannerService.createPlan(eq(userId), any())).thenReturn(plan);
        when(aiProxyService.createTextToImageJobsFromAgent(eq(userId), eq("生成3张小猫"), eq("1:1"), eq(3)))
                .thenReturn(List.of(job("job-1"), job("job-2"), job("job-3")));

        AiProxyDtos.AgentRunResponse response = service.run(userId, request);

        assertThat(response.jobs()).extracting(AiProxyDtos.AgentRunJob::jobId)
                .containsExactly("job-1", "job-2", "job-3");
        assertThat(response.displayTitle()).isEqualTo("小猫图片");
        assertThat(response.displayMessage()).isEqualTo("我开始为你生成 3 张小猫图片。");
        verify(aiProxyService).createTextToImageJobsFromAgent(eq(userId), eq("生成3张小猫"), eq("1:1"), eq(3));
        verify(aiProxyService, never()).createTextToImageJobFromAgent(any(), any(), any());
        verify(eventBroadcaster, times(5)).publishAgentRunEvent(eq(userId), eq("run-generate"), any());
    }

    @Test
    void imageEditPlanCreatesBackendEditJobWithAssetIdsAndRolePrompt() throws IOException {
        AgentPlannerService plannerService = mock(AgentPlannerService.class);
        AiProxyService aiProxyService = mock(AiProxyService.class);
        ImageJobEventBroadcaster eventBroadcaster = mock(ImageJobEventBroadcaster.class);
        AgentRunService service = new AgentRunService(plannerService, aiProxyService, eventBroadcaster);
        UUID userId = UUID.randomUUID();
        UUID personAssetId = UUID.randomUUID();
        UUID shoeAssetId = UUID.randomUUID();
        AiProxyDtos.AgentRunRequest request = new AiProxyDtos.AgentRunRequest(
                "把@shoe 放在@person 的脚上",
                "person",
                "auto",
                "",
                List.of(
                        new AiProxyDtos.ImageReferenceCandidate("person", "人物图", 1, 512, 768, personAssetId.toString()),
                        new AiProxyDtos.ImageReferenceCandidate("shoe", "红色鞋子", 2, 300, 300, shoeAssetId.toString())
                ),
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
