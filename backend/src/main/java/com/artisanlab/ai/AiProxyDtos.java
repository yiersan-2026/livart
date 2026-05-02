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
            @Size(max = 10) String imageResolution,
            @Size(max = 40) String requestedEditMode,
            @Valid ProductPosterRequest productPoster,
            @Valid
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images
    ) {
        public AgentPlanRequest(
                String prompt,
                String contextImageId,
                String aspectRatio,
                String imageResolution,
                String requestedEditMode,
                List<ImageReferenceCandidate> images
        ) {
            this(prompt, contextImageId, aspectRatio, imageResolution, requestedEditMode, null, images);
        }
    }

    public record ProductPosterRequest(
            @Size(max = 12) List<@Size(max = 120) String> productImageIds,
            @Size(max = 120) String productImageId,
            @Size(max = 2000) String productDescription,
            @Size(max = 120) String productName,
            @Size(max = 120) String industry,
            @Size(max = 200) String material,
            @Size(max = 120) String size,
            @Size(max = 120) String color,
            @Size(max = 200) String style,
            @Size(max = 400) String scenarios,
            @Size(max = 400) String targetAudience,
            @Size(max = 800) String sellingPoints,
            @Size(max = 1000) String extraDetails,
            @Size(max = 120) String platformStyle,
            Integer posterCount,
            @Size(max = 20) String productMode,
            @Size(max = 6000) String conversationContext,
            @Size(max = 240) String detailDesignStyle
    ) {
        public ProductPosterRequest(
                List<String> productImageIds,
                String productImageId,
                String productDescription,
                String productName,
                String industry,
                String material,
                String size,
                String color,
                String style,
                String scenarios,
                String targetAudience,
                String sellingPoints,
                String extraDetails,
                String platformStyle,
                Integer posterCount,
                String productMode
        ) {
            this(
                    productImageIds,
                    productImageId,
                    productDescription,
                    productName,
                    industry,
                    material,
                    size,
                    color,
                    style,
                    scenarios,
                    targetAudience,
                    sellingPoints,
                    extraDetails,
                    platformStyle,
                    posterCount,
                    productMode,
                    "",
                    ""
            );
        }

        public ProductPosterRequest(
                List<String> productImageIds,
                String productImageId,
                String productDescription,
                String productName,
                String industry,
                String material,
                String size,
                String color,
                String style,
                String scenarios,
                String targetAudience,
                String sellingPoints,
                String extraDetails,
                String platformStyle,
                Integer posterCount
        ) {
            this(
                    productImageIds,
                    productImageId,
                    productDescription,
                    productName,
                    industry,
                    material,
                    size,
                    color,
                    style,
                    scenarios,
                    targetAudience,
                    sellingPoints,
                    extraDetails,
                    platformStyle,
                    posterCount,
                    "single",
                    "",
                    ""
            );
        }

        public ProductPosterRequest(
                List<String> productImageIds,
                String productImageId,
                String productDescription,
                String productName,
                String material,
                String size,
                String color,
                String style,
                String scenarios,
                String targetAudience,
                String sellingPoints,
                String platformStyle,
                Integer posterCount
        ) {
            this(
                    productImageIds,
                    productImageId,
                    productDescription,
                    productName,
                    "",
                    material,
                    size,
                    color,
                    style,
                    scenarios,
                    targetAudience,
                    sellingPoints,
                    "",
                    platformStyle,
                    posterCount,
                    "single",
                    "",
                    ""
            );
        }

        public List<String> normalizedProductImageIds() {
            List<String> ids = productImageIds == null
                    ? List.of()
                    : productImageIds.stream()
                            .filter(id -> id != null && !id.isBlank())
                            .map(String::trim)
                            .distinct()
                            .limit(12)
                            .toList();
            if (!ids.isEmpty()) {
                return ids;
            }
            return productImageId == null || productImageId.isBlank() ? List.of() : List.of(productImageId.trim());
        }

        public String normalizedProductMode() {
            return "series".equalsIgnoreCase(productMode == null ? "" : productMode.trim()) ? "series" : "single";
        }
    }

    public record ProductPosterAnalysisRequest(
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 1200) String latestUserMessage,
            @Size(max = 6000) String conversationContext,
            @Valid
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images
    ) {
        public ProductPosterAnalysisRequest(String description) {
            this(description, "", "", List.of());
        }

        public ProductPosterAnalysisRequest(String description, List<ImageReferenceCandidate> images) {
            this(description, "", "", images);
        }

        public ProductPosterAnalysisRequest(
                String description,
                List<ImageReferenceCandidate> images,
                String latestUserMessage,
                String conversationContext
        ) {
            this(description, latestUserMessage, conversationContext, images);
        }
    }

    public record ProductPosterFact(
            @Size(max = 60) String key,
            @Size(max = 80) String label,
            @Size(max = 400) String value,
            @Size(max = 40) String source,
            @Size(max = 20) String confidence,
            @Size(max = 200) String note
    ) {
    }

    public record ProductPosterAnalysisResponse(
            String summary,
            String productName,
            String industry,
            String material,
            String size,
            String color,
            String style,
            String detailDesignStyle,
            String scenarios,
            String targetAudience,
            String sellingPoints,
            String extraDetails,
            String platformStyle,
            List<ProductPosterFact> confirmedFacts,
            List<ProductPosterFact> suggestedFacts,
            List<String> missingInformation,
            String nextQuestion,
            boolean readyToGenerate,
            String assistantMessage
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
            @Size(max = 10) String imageResolution,
            @Size(max = 40) String requestedEditMode,
            @Valid
            @Size(max = 12)
            List<@NotNull ImageReferenceCandidate> images,
            @Size(max = 8_000_000) String maskDataUrl,
            @Size(max = 80) String forcedToolId,
            @Size(max = 120) String externalSkillId,
            @Valid ProductPosterRequest productPoster,
            @Size(max = 80) String clientRunId
    ) {
        public AgentRunRequest(
                String prompt,
                String contextImageId,
                String aspectRatio,
                String imageResolution,
                String requestedEditMode,
                List<ImageReferenceCandidate> images,
                String maskDataUrl,
                String forcedToolId,
                String externalSkillId,
                String clientRunId
        ) {
            this(prompt, contextImageId, aspectRatio, imageResolution, requestedEditMode, images, maskDataUrl, forcedToolId, externalSkillId, null, clientRunId);
        }

        AgentPlanRequest toPlanRequest() {
            return new AgentPlanRequest(prompt, contextImageId, aspectRatio, imageResolution, requestedEditMode, productPoster, images);
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
