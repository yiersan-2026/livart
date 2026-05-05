package com.artisanlab.external;

import com.artisanlab.asset.AssetService;
import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalImageServiceTest {
    @Test
    void parsesGogoToolImageResponse() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/external/images", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBytes = """
                    {
                      "data": [
                        {
                          "url": "https://wx2.sinaimg.cn/large/xxx.jpg",
                          "previewUrl": "https://wx2.sinaimg.cn/wap360/xxx.jpg",
                          "formatLabel": "图片 1",
                          "ext": "jpg",
                          "mimeType": "image/jpeg",
                          "width": 2048,
                          "height": 1152,
                          "fileSizeBytes": null,
                          "watermarked": false,
                          "sortOrder": 0
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            ExternalImageParseHistoryMapper parseHistoryMapper = mock(ExternalImageParseHistoryMapper.class);
            ExternalImageService service = new ExternalImageService(
                    properties("http://127.0.0.1:%d/api/v1/external/images".formatted(server.getAddress().getPort())),
                    new ObjectMapper(),
                    mock(AssetService.class),
                    parseHistoryMapper
            );
            UUID userId = UUID.randomUUID();

            ExternalImageDtos.SearchResponse response = service.search(
                    userId,
                    new ExternalImageDtos.SearchRequest("世界尚未完蛋 蜜蜂用尾巴缝制肿胀 肆月是一场拥挤的新... http://xhslink.com/o/6b7jkVrCaVu  把这段话复制下来，打开【小红书】查看。")
            );

            assertThat(apiKey.get()).isEqualTo("test-key");
            assertThat(requestBody.get()).contains("\"url\":\"http://xhslink.com/o/6b7jkVrCaVu\"");
            assertThat(response.images()).hasSize(1);
            ExternalImageDtos.ImageCandidate image = response.images().get(0);
            assertThat(image.url()).isEqualTo("https://wx2.sinaimg.cn/large/xxx.jpg");
            assertThat(image.thumbnailUrl()).isEqualTo("https://wx2.sinaimg.cn/wap360/xxx.jpg");
            assertThat(image.formatLabel()).isEqualTo("图片 1");
            assertThat(image.title()).isEqualTo("图片 1");
            assertThat(image.mimeType()).isEqualTo("image/jpeg");
            assertThat(image.width()).isEqualTo(2048);
            assertThat(image.height()).isEqualTo(1152);
            assertThat(image.watermarked()).isFalse();
            assertThat(image.sortOrder()).isZero();

            ArgumentCaptor<ExternalImageParseHistoryEntity> historyCaptor = ArgumentCaptor.forClass(ExternalImageParseHistoryEntity.class);
            verify(parseHistoryMapper).upsert(historyCaptor.capture());
            assertThat(historyCaptor.getValue().getUserId()).isEqualTo(userId);
            assertThat(historyCaptor.getValue().getSourceUrl()).isEqualTo("http://xhslink.com/o/6b7jkVrCaVu");
            assertThat(historyCaptor.getValue().getSourceHost()).isEqualTo("xhslink.com");
            assertThat(historyCaptor.getValue().getImageCount()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesUpstreamErrorCodeInMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/external/images", exchange -> {
            byte[] responseBytes = """
                    {
                      "error": {
                        "code": "invalid_api_key",
                        "message": "API Key 不正确",
                        "details": []
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            ExternalImageParseHistoryMapper parseHistoryMapper = mock(ExternalImageParseHistoryMapper.class);
            ExternalImageService service = new ExternalImageService(
                    properties("http://127.0.0.1:%d/api/v1/external/images".formatted(server.getAddress().getPort())),
                    new ObjectMapper(),
                    mock(AssetService.class),
                    parseHistoryMapper
            );

            assertThatThrownBy(() -> service.search(UUID.randomUUID(), new ExternalImageDtos.SearchRequest("https://example.com/post")))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("API Key 不正确")
                    .hasMessageContaining("invalid_api_key")
                    .hasMessageContaining("状态 401");
            verify(parseHistoryMapper, never()).upsert(org.mockito.ArgumentMatchers.any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsRecentParseHistory() {
        ExternalImageParseHistoryMapper parseHistoryMapper = mock(ExternalImageParseHistoryMapper.class);
        UUID userId = UUID.randomUUID();
        ExternalImageParseHistoryEntity historyEntity = new ExternalImageParseHistoryEntity();
        historyEntity.setUserId(userId);
        historyEntity.setSourceUrl("https://www.xiaohongshu.com/explore/abc");
        historyEntity.setSourceHost("www.xiaohongshu.com");
        historyEntity.setImageCount(3);
        historyEntity.setLastParsedAt(OffsetDateTime.parse("2026-05-01T10:15:30+08:00"));
        when(parseHistoryMapper.selectRecentByUserId(userId, 8)).thenReturn(List.of(historyEntity));

        ExternalImageService service = new ExternalImageService(
                properties("http://127.0.0.1:65535/api/v1/external/images"),
                new ObjectMapper(),
                mock(AssetService.class),
                parseHistoryMapper
        );

        ExternalImageDtos.ParseHistoryResponse response = service.loadParseHistory(userId);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).sourceUrl()).isEqualTo("https://www.xiaohongshu.com/explore/abc");
        assertThat(response.items().get(0).sourceHost()).isEqualTo("www.xiaohongshu.com");
        assertThat(response.items().get(0).imageCount()).isEqualTo(3);
        assertThat(response.items().get(0).lastParsedAt()).isEqualTo(OffsetDateTime.parse("2026-05-01T10:15:30+08:00"));
    }

    private ArtisanProperties properties(String endpoint) {
        return new ArtisanProperties(
                new ArtisanProperties.Auth("test-secret-at-least-long-enough", 7),
                new ArtisanProperties.Canvas(UUID.randomUUID()),
                new ArtisanProperties.Cors("*"),
                new ArtisanProperties.Minio("http://localhost:9000", "minio", "minio123", "bucket"),
                new ArtisanProperties.Rabbitmq("canvas-save", "canvas-save-dlq"),
                new ArtisanProperties.ExternalImages(endpoint, "test-key", 5),
                new ArtisanProperties.ExternalApi(false, "X-Livart-Api-Key", "")
        );
    }
}
