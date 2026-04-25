package com.artisanlab.canvas;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class CanvasSaveQueueService {
    private final RabbitTemplate rabbitTemplate;
    private final ArtisanProperties properties;

    public CanvasSaveQueueService(RabbitTemplate rabbitTemplate, ArtisanProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    public CanvasDtos.CanvasResponse enqueueCurrentCanvasSave(UUID userId, CanvasDtos.SaveCanvasRequest request) {
        return enqueueCanvasSave(userId, properties.canvas().defaultCanvasId(), request);
    }

    public CanvasDtos.CanvasResponse enqueueCanvasSave(UUID userId, UUID canvasId, CanvasDtos.SaveCanvasRequest request) {
        String title = normalizeTitle(request.title());
        long revision = normalizeRevision(request.clientRevision());
        CanvasSaveMessage message = new CanvasSaveMessage(
                UUID.randomUUID(),
                userId,
                canvasId,
                title,
                request.state().toString(),
                revision,
                OffsetDateTime.now()
        );

        try {
            rabbitTemplate.convertAndSend(properties.rabbitmq().canvasSaveQueue(), message, rabbitMessage -> {
                rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return rabbitMessage;
            });
        } catch (AmqpException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CANVAS_SAVE_QUEUE_UNAVAILABLE", "画布保存队列暂时不可用");
        }

        return new CanvasDtos.CanvasResponse(
                message.canvasId(),
                title,
                request.state(),
                null,
                message.requestedAt(),
                revision,
                true
        );
    }

    private long normalizeRevision(Long clientRevision) {
        if (clientRevision != null && clientRevision > 0) {
            return clientRevision;
        }
        return System.currentTimeMillis();
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "默认画布";
        }
        String trimmed = title.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
