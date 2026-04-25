package com.artisanlab;

import com.artisanlab.config.ArtisanProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ArtisanProperties.class)
public class ArtisanLabBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArtisanLabBackendApplication.class, args);
    }
}
