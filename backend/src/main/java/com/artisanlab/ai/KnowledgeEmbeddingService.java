package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeEmbeddingService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

    private final ObjectMapper objectMapper;
    private final String embeddingModel;
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;

    public KnowledgeEmbeddingService(
            ObjectMapper objectMapper,
            @Value("${artisan.knowledge.embedding-model:BAAI/bge-m3}") String embeddingModel,
            @Value("${artisan.knowledge.embedding-base-url:https://api.siliconflow.cn/v1}") String embeddingBaseUrl,
            @Value("${artisan.knowledge.embedding-api-key:}") String embeddingApiKey
    ) {
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank()
                ? "BAAI/bge-m3"
                : embeddingModel.trim();
        this.embeddingBaseUrl = embeddingBaseUrl == null ? "" : embeddingBaseUrl.trim();
        this.embeddingApiKey = embeddingApiKey == null ? "" : embeddingApiKey.trim();
    }

    public boolean hasUsableConfig(UserApiConfigDtos.ResolvedConfig config) {
        return !resolveBaseUrl(config).isBlank() && !resolveApiKey(config).isBlank();
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public List<Double> createEmbedding(UserApiConfigDtos.ResolvedConfig config, String input) {
        String normalizedInput = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalizedInput.isBlank()) {
            return List.of();
        }

        String baseUrl = resolveBaseUrl(config);
        String apiKey = resolveApiKey(config);
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "KNOWLEDGE_EMBEDDING_CONFIG_MISSING",
                    "知识库向量生成配置缺失"
            );
        }

        try {
            String responseText = RestClient.builder()
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(resolveEmbeddingsUrl(baseUrl))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", embeddingModel,
                            "input", normalizedInput
                    ))
                    .retrieve()
                    .body(String.class);
            return parseEmbedding(responseText);
        } catch (RestClientResponseException exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "KNOWLEDGE_EMBEDDING_UPSTREAM_ERROR",
                    "知识库向量生成失败：上游返回状态 %d".formatted(exception.getStatusCode().value())
            );
        }
    }

    private String resolveBaseUrl(UserApiConfigDtos.ResolvedConfig config) {
        if (!embeddingBaseUrl.isBlank()) {
            return embeddingBaseUrl;
        }
        return "";
    }

    private String resolveApiKey(UserApiConfigDtos.ResolvedConfig config) {
        if (!embeddingApiKey.isBlank()) {
            return embeddingApiKey;
        }
        return "";
    }

    public static String toVectorLiteral(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return "[]";
        }

        List<String> values = new ArrayList<>();
        for (Double value : embedding) {
            if (value != null && Double.isFinite(value)) {
                values.add(Double.toString(value));
            }
        }
        return "[" + String.join(",", values) + "]";
    }

    private List<Double> parseEmbedding(String responseText) {
        try {
            JsonNode root = objectMapper.readTree(responseText == null ? "" : responseText);
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray()) {
                throw new IllegalStateException("embedding response missing data[0].embedding");
            }

            List<Double> values = new ArrayList<>();
            for (JsonNode item : embeddingNode) {
                if (item.isNumber()) {
                    values.add(item.asDouble());
                }
            }
            if (values.isEmpty()) {
                throw new IllegalStateException("embedding response is empty");
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "KNOWLEDGE_EMBEDDING_PARSE_FAILED",
                    "知识库向量结果解析失败"
            );
        }
    }


    private String resolveEmbeddingsUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/embeddings";
        }
        return normalized + "/v1/embeddings";
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
