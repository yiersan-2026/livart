package com.artisanlab.config;

import com.artisanlab.ai.ImageJobWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ImageJobWebSocketHandler imageJobWebSocketHandler;

    public WebSocketConfig(ImageJobWebSocketHandler imageJobWebSocketHandler) {
        this.imageJobWebSocketHandler = imageJobWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(imageJobWebSocketHandler, "/ws/image-jobs")
                .setAllowedOriginPatterns("*");
    }
}
