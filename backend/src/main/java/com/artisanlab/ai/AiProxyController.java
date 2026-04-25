package com.artisanlab.ai;

import com.artisanlab.auth.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/prompts/optimize")
    public ResponseEntity<Map<String, Object>> optimizePrompt(@RequestBody AiProxyDtos.PromptOptimizeRequest request) {
        return aiProxyService.optimizePrompt(authContext.requireUserId(), request);
    }
}
