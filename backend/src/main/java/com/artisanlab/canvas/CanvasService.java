package com.artisanlab.canvas;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CanvasService {
    private final CanvasMapper canvasMapper;
    private final CanvasSnapshotMapper snapshotMapper;
    private final ArtisanProperties properties;
    private final ObjectMapper objectMapper;

    public CanvasService(
            CanvasMapper canvasMapper,
            CanvasSnapshotMapper snapshotMapper,
            ArtisanProperties properties,
            ObjectMapper objectMapper
    ) {
        this.canvasMapper = canvasMapper;
        this.snapshotMapper = snapshotMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CanvasDtos.CanvasResponse getCurrentCanvas(UUID userId) {
        return getCanvas(userId, defaultCanvasId());
    }

    @Transactional(readOnly = true)
    public CanvasDtos.CanvasResponse getCanvas(UUID userId, UUID canvasId) {
        CanvasEntity entity = canvasMapper.findByIdAndUserIdWithJson(canvasId, userId);
        if (entity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CANVAS_NOT_FOUND", "项目画布不存在");
        }

        return toResponse(entity);
    }

    @Transactional
    public List<CanvasDtos.CanvasSummary> listCanvases(UUID userId) {
        List<CanvasEntity> summaries = canvasMapper.listSummariesByUserId(userId);
        if (summaries.isEmpty()) {
            createCanvas(userId, new CanvasDtos.CreateCanvasRequest("默认画布"));
            summaries = canvasMapper.listSummariesByUserId(userId);
        }

        return summaries.stream().map(this::toSummary).toList();
    }

    @Transactional
    public CanvasDtos.CanvasResponse createCanvas(UUID userId, CanvasDtos.CreateCanvasRequest request) {
        CanvasEntity canvas = new CanvasEntity();
        canvas.setId(UUID.randomUUID());
        canvas.setUserId(userId);
        canvas.setTitle(normalizeTitle(request.title()));
        canvasMapper.insertEmpty(canvas);
        return toResponse(canvasMapper.findByIdAndUserIdWithJson(canvas.getId(), userId));
    }

    @Transactional
    public boolean persistQueuedCanvasSave(CanvasSaveMessage message) {
        CanvasEntity canvas = new CanvasEntity();
        canvas.setId(message.canvasId());
        canvas.setUserId(message.userId());
        canvas.setTitle(message.title());
        canvas.setStateJson(message.stateJson());
        canvas.setRevision(message.revision());
        int affectedRows = canvasMapper.upsertIfNewer(canvas);
        if (affectedRows == 0) {
            return false;
        }

        CanvasSnapshotEntity snapshot = new CanvasSnapshotEntity();
        snapshot.setId(UUID.randomUUID());
        snapshot.setCanvasId(message.canvasId());
        snapshot.setStateJson(message.stateJson());
        snapshot.setRevision(message.revision());
        snapshotMapper.insertSnapshotIfDue(snapshot);
        return true;
    }

    public UUID defaultCanvasId() {
        return properties.canvas().defaultCanvasId();
    }

    private CanvasDtos.CanvasResponse toResponse(CanvasEntity entity) {
        return new CanvasDtos.CanvasResponse(
                entity.getId(),
                entity.getTitle(),
                parseState(entity.getStateJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getRevision() == null ? 0L : entity.getRevision(),
                false
        );
    }

    private CanvasDtos.CanvasSummary toSummary(CanvasEntity entity) {
        return new CanvasDtos.CanvasSummary(
                entity.getId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getRevision() == null ? 0L : entity.getRevision()
        );
    }

    private JsonNode parseState(String stateJson) {
        try {
            return objectMapper.readTree(stateJson == null || stateJson.isBlank() ? "{}" : stateJson);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CANVAS_STATE_CORRUPTED", "画布状态数据损坏");
        }
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "未命名项目";
        }
        String trimmed = title.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

}
