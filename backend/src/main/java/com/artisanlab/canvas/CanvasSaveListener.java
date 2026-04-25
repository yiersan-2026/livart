package com.artisanlab.canvas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CanvasSaveListener {
    private static final Logger log = LoggerFactory.getLogger(CanvasSaveListener.class);

    private final CanvasService canvasService;

    public CanvasSaveListener(CanvasService canvasService) {
        this.canvasService = canvasService;
    }

    @RabbitListener(queues = "${artisan.rabbitmq.canvas-save-queue}")
    public void handle(CanvasSaveMessage message) {
        boolean persisted = canvasService.persistQueuedCanvasSave(message);
        if (persisted) {
            log.info("Persisted canvas save messageId={} canvasId={} revision={}", message.messageId(), message.canvasId(), message.revision());
        } else {
            log.info("Skipped stale canvas save messageId={} canvasId={} revision={}", message.messageId(), message.canvasId(), message.revision());
        }
    }
}
