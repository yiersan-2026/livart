package com.artisanlab.externalapi;

import com.artisanlab.ai.AiProxyService;
import com.artisanlab.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    @PostMapping("/edits")
    public ApiResponse<Map<String, Object>> createImageEditJob(
            @Valid @RequestBody ExternalAiImageDtos.ImageToImageRequest request,
            HttpServletRequest httpServletRequest
    ) throws IOException {
        UUID ownerId = externalApiKeyAuthService.requireAuthorizedOwner(httpServletRequest);
        return ApiResponse.ok(aiProxyService.createExternalImageEditJob(ownerId, request));
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
