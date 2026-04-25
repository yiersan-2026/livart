package com.artisanlab.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    private final ArtisanProperties properties;

    public RabbitMqConfig(ArtisanProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Queue canvasSaveQueue() {
        return QueueBuilder.durable(properties.rabbitmq().canvasSaveQueue())
                .deadLetterExchange("")
                .deadLetterRoutingKey(properties.rabbitmq().canvasSaveDeadLetterQueue())
                .build();
    }

    @Bean
    public Queue canvasSaveDeadLetterQueue() {
        return QueueBuilder.durable(properties.rabbitmq().canvasSaveDeadLetterQueue()).build();
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
