package com.artisanlab.externalapi;

import com.artisanlab.ai.AiProxyService;
import com.artisanlab.common.ApiResponse;
import com.artisanlab.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/external/v1/images")
public class ExternalAiImageController {
    private final AiProxyService aiProxyService;
    private final ExternalApiKeyAuthService externalApiKeyAuthService;

    public ExternalAiImageController(
            AiProxyService aiProxyService,
            ExternalApiKeyAuthService externalApiKeyAuthService
    ) {
        this.aiProxyService = aiProxyService;
        this.externalApiKeyAuthService = externalApiKeyAuthService;
    }

    @PostMapping("/generations")
    public ApiResponse<Map<String, Object>> createTextToImageJob(
            @Valid @RequestBody ExternalAiImageDtos.TextToImageRequest request,
            HttpServletRequest httpServletRequest
    ) throws IOException {
        UUID ownerId = externalApiKeyAuthService.requireAuthorizedOwner(httpServletRequest);
        return ApiResponse.ok(aiProxyService.createExternalTextToImageJob(ownerId, request));
    }

    @PostMapping(
            value = "/edits",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ApiResponse<Map<String, Object>> createImageEditJob(
            @Valid @RequestBody ExternalAiImageDtos.ImageToImageRequest request,
            HttpServletRequest httpServletRequest
    ) throws IOException {
        UUID ownerId = externalApiKeyAuthService.requireAuthorizedOwner(httpServletRequest);
        return ApiResponse.ok(aiProxyService.createExternalImageEditJob(ownerId, request));
    }

    @PostMapping(
            value = "/edits",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<Map<String, Object>> createImageEditJobMultipart(
            @Valid @ModelAttribute ExternalAiImageDtos.ImageToImageMultipartRequest request,
            HttpServletRequest httpServletRequest
    ) throws IOException {
        UUID ownerId = externalApiKeyAuthService.requireAuthorizedOwner(httpServletRequest);
        return ApiResponse.ok(aiProxyService.createExternalImageEditJob(ownerId, toJsonRequest(request)));
    }

    private ExternalAiImageDtos.ImageToImageRequest toJsonRequest(
            ExternalAiImageDtos.ImageToImageMultipartRequest request
    ) {
        String imageBase64 = request.imageBase64OrNull();
        if ((imageBase64 == null || imageBase64.isBlank()) && request.image() != null && !request.image().isEmpty()) {
            imageBase64 = request.imageDataUrl();
        }
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "原图不能为空");
        }

        String maskBase64 = request.maskBase64OrNull();
        if ((maskBase64 == null || maskBase64.isBlank()) && request.mask() != null && !request.mask().isEmpty()) {
            maskBase64 = request.maskDataUrl();
        }

        return new ExternalAiImageDtos.ImageToImageRequest(
                request.prompt(),
                imageBase64,
                maskBase64,
                request.referenceImageDataUrls(),
                request.aspectRatio(),
                request.imageResolution(),
                request.enablePromptOptimization()
        );
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> getImageJob(
            @PathVariable String jobId,
            HttpServletRequest httpServletRequest
    ) {
        UUID ownerId = externalApiKeyAuthService.requireAuthorizedOwner(httpServletRequest);
        return ApiResponse.ok(aiProxyService.getExternalImageJobSnapshot(ownerId, jobId));
    }
}
