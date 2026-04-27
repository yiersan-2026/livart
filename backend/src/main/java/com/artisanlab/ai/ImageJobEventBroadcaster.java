package com.artisanlab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImageJobEventBroadcaster {
    public static final String USER_ID_ATTRIBUTE = "livart.userId";

    private static final Logger log = LoggerFactory.getLogger(ImageJobEventBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public ImageJobEventBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(UUID userId, WebSocketSession session) {
        session.getAttributes().put(USER_ID_ATTRIBUTE, userId);
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(WebSocketSession session) {
        Object userId = session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (userId instanceof UUID uuid) {
            Set<WebSocketSession> sessions = sessionsByUser.get(uuid);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    sessionsByUser.remove(uuid);
                }
            }
            return;
        }

        sessionsByUser.values().forEach(sessions -> sessions.remove(session));
    }

    public void publishImageJob(UUID userId, Map<String, Object> job) {
        sendToUser(userId, Map.of(
                "type", "image-job",
                "job", job
        ));
    }

    public void publishAgentRunEvent(UUID userId, String runId, Map<String, Object> event) {
        if (runId == null || runId.isBlank()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "agent-run-event");
        payload.put("runId", runId);
        payload.put("event", event);
        sendToUser(userId, payload);
    }

    public void sendToSession(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) {
            unregister(session);
            return;
        }

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (IOException exception) {
            log.warn("[image-job-ws] send failed sessionId={} error={}", session.getId(), exception.getMessage());
            unregister(session);
        }
    }

    private void sendToUser(UUID userId, Map<String, Object> payload) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        sessions.forEach(session -> sendToSession(session, payload));
    }
}
