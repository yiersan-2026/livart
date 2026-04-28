package com.artisanlab.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiProxyDtos {
    private AiProxyDtos() {
    }

    public record ImageReferenceCandidate(
            @NotBlank @Size(max = 120) String id,
            @Size(max = 200) String name,
            Integer index,
            Integer width,
            Integer height,
            @Size(max = 80) String assetId
    ) {
        public ImageReferenceCandidate(String id, String name, Integer index, Integer width, Integer height) {
            this(id, name, index, width, height, "");
        }
    }

    public record ImageReferenceAnalysisRequest(
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 120) String contextImageId,
            @Valid
            @NotEmpty
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images
    ) {
    }

    public record ImageReferenceAnalysisResponse(
            String baseImageId,
            List<String> referenceImageIds,
            String reason,
            String source
    ) {
    }

    public record AgentPlanRequest(
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 120) String contextImageId,
            @Size(max = 20) String aspectRatio,
            @Size(max = 40) String requestedEditMode,
            @Valid
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images
    ) {
    }

    public record AgentPlanStep(
            @NotBlank @Size(max = 60) String id,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 200) String description,
            @NotBlank @Size(max = 40) String type
    ) {
    }

    public record AgentPlanResponse(
            boolean allowed,
            String responseMode,
            String rejectionMessage,
            String answerMessage,
            String taskType,
            String mode,
            int count,
            String baseImageId,
            List<String> referenceImageIds,
            String aspectRatio,
            String summary,
            String displayTitle,
            String displayMessage,
            List<String> thinkingSteps,
            List<AgentPlanStep> steps,
            String source
    ) {
    }

    public record AgentRunRequest(
            @NotBlank @Size(max = 4000) String prompt,
            @Size(max = 120) String contextImageId,
            @Size(max = 20) String aspectRatio,
            @Size(max = 40) String requestedEditMode,
            @Valid
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images,
            @Size(max = 8_000_000) String maskDataUrl,
            @Size(max = 80) String clientRunId
    ) {
        AgentPlanRequest toPlanRequest() {
            return new AgentPlanRequest(prompt, contextImageId, aspectRatio, requestedEditMode, images);
        }
    }

    public record AgentRunJob(
            @NotBlank String jobId,
            @NotBlank String status,
            String originalPrompt,
            String optimizedPrompt
    ) {
    }

    public record AgentRunResponse(
            boolean allowed,
            String responseMode,
            String rejectionMessage,
            String answerMessage,
            AgentPlanResponse plan,
            String taskType,
            String mode,
            int count,
            String baseImageId,
            List<String> referenceImageIds,
            String aspectRatio,
            String requestPrompt,
            String displayTitle,
            String displayMessage,
            List<AgentRunJob> jobs,
            String source
    ) {
    }

    public record AgentRunStatusResponse(
            String runId,
            String status,
            AgentRunResponse response,
            String errorMessage,
            String errorCode,
            long updatedAt
    ) {
    }
}
