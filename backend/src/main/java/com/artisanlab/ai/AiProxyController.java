package com.artisanlab.ai;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AiProxyController {
    private final AiProxyService aiProxyService;
    private final AuthContext authContext;

    public AiProxyController(AiProxyService aiProxyService, AuthContext authContext) {
        this.aiProxyService = aiProxyService;
        this.authContext = authContext;
    }

    @PostMapping("/images/generations")
    public ResponseEntity<byte[]> textToImage(HttpServletRequest request) throws IOException {
        return aiProxyService.proxyImageRequest(authContext.requireUserId(), "text-to-image", "images/generations", request);
    }

    @PostMapping("/images/edits")
    public ResponseEntity<byte[]> imageToImage(HttpServletRequest request) throws IOException {
        return aiProxyService.proxyImageRequest(authContext.requireUserId(), "image-to-image", "images/edits", request);
    }

    @PostMapping("/image-jobs/generations")
    public ResponseEntity<Map<String, Object>> createTextToImageJob(HttpServletRequest request) throws IOException {
        return aiProxyService.createImageJob(authContext.requireUserId(), "text-to-image", "images/generations", request);
    }

    @PostMapping("/image-jobs/edits")
    public ResponseEntity<Map<String, Object>> createImageToImageJob(HttpServletRequest request) throws IOException {
        return aiProxyService.createImageJob(authContext.requireUserId(), "image-to-image", "images/edits", request);
    }

    @GetMapping("/image-jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getImageJob(@PathVariable String jobId) {
        return aiProxyService.getImageJob(authContext.requireUserId(), jobId);
    }

    @PostMapping("/image-references/analyze")
    public ApiResponse<AiProxyDtos.ImageReferenceAnalysisResponse> analyzeImageReferences(
            @Valid @RequestBody AiProxyDtos.ImageReferenceAnalysisRequest request
    ) {
        return ApiResponse.ok(aiProxyService.analyzeImageReferences(authContext.requireUserId(), request));
    }
}
