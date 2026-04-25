package com.artisanlab.ai;

import com.artisanlab.auth.AuthDtos;
import com.artisanlab.auth.AuthService;
import com.artisanlab.auth.JwtService;
import com.artisanlab.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ImageJobWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ImageJobWebSocketHandler.class);

    private final JwtService jwtService;
    private final AuthService authService;
    private final AiProxyService aiProxyService;
    private final ImageJobEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public ImageJobWebSocketHandler(
            JwtService jwtService,
            AuthService authService,
            AiProxyService aiProxyService,
            ImageJobEventBroadcaster broadcaster,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.aiProxyService = aiProxyService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.sendToSession(session, Map.of("type", "connected"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception exception) {
            broadcaster.sendToSession(session, Map.of(
                    "type", "error",
                    "error", Map.of("message", "WebSocket 消息格式无效", "code", "INVALID_MESSAGE")
            ));
            return;
        }

        String type = payload.path("type").asText("");
        if ("auth".equals(type)) {
            authenticate(session, payload);
            return;
        }

        UUID userId = currentUserId(session);
        if (userId == null) {
            broadcaster.sendToSession(session, Map.of(
                    "type", "error",
                    "error", Map.of("message", "请先登录", "code", "AUTH_REQUIRED")
            ));
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("auth required"));
            return;
        }

        if ("subscribe".equals(type)) {
            sendJobSnapshot(session, userId, payload.path("jobId").asText(""));
            return;
        }

        if ("ping".equals(type)) {
            broadcaster.sendToSession(session, Map.of("type", "pong"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[image-job-ws] transport error sessionId={} error={}", session.getId(), exception.getMessage());
        broadcaster.unregister(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }

    private void authenticate(WebSocketSession session, JsonNode payload) throws Exception {
        String token = payload.path("token").asText("");
        try {
            if (token.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
            }

            UUID userId = jwtService.verifyAndReadUserId(token);
            AuthDtos.AuthUser user = authService.findUserById(userId);
            if (user == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录状态已失效，请重新登录");
            }

            broadcaster.register(user.id(), session);
            broadcaster.sendToSession(session, Map.of("type", "authenticated"));

            String jobId = payload.path("jobId").asText("");
            if (!jobId.isBlank()) {
                sendJobSnapshot(session, user.id(), jobId);
            }
        } catch (ApiException exception) {
            broadcaster.sendToSession(session, Map.of(
                    "type", "error",
                    "error", toErrorPayload(exception)
            ));
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("auth failed"));
        }
    }

    private void sendJobSnapshot(WebSocketSession session, UUID userId, String jobId) {
        try {
            Map<String, Object> job = aiProxyService.getImageJobSnapshot(userId, jobId);
            broadcaster.sendToSession(session, Map.of(
                    "type", "image-job",
                    "job", job
            ));
        } catch (ApiException exception) {
            broadcaster.sendToSession(session, Map.of(
                    "type", "image-job-error",
                    "jobId", jobId,
                    "error", toErrorPayload(exception)
            ));
        }
    }

    private UUID currentUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get(ImageJobEventBroadcaster.USER_ID_ATTRIBUTE);
        return userId instanceof UUID uuid ? uuid : null;
    }

    private Map<String, Object> toErrorPayload(ApiException exception) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", exception.getMessage());
        error.put("code", exception.code());
        error.put("status", exception.status().value());
        return error;
    }
}
