package com.artisanlab.ai;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
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
    private final AgentRunService agentRunService;
    private final AuthContext authContext;

    public AiProxyController(
            AiProxyService aiProxyService,
            AgentRunService agentRunService,
            AuthContext authContext
    ) {
        this.aiProxyService = aiProxyService;
        this.agentRunService = agentRunService;
        this.authContext = authContext;
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

    @PostMapping("/agent/runs")
    public ApiResponse<AiProxyDtos.AgentRunResponse> runAgentTask(
            @Valid @RequestBody AiProxyDtos.AgentRunRequest request
    ) throws IOException {
        return ApiResponse.ok(agentRunService.run(authContext.requireUserId(), request));
    }
}
