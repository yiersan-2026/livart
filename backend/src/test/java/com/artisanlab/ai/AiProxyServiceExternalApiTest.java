package com.artisanlab.ai;

import com.artisanlab.asset.AssetService;
import com.artisanlab.config.ArtisanProperties;
import com.artisanlab.externalapi.ExternalAiImageDtos;
import com.artisanlab.externalapi.ExternalImageResultService;
import com.artisanlab.userconfig.UserApiConfigMapper;
import com.artisanlab.userconfig.UserApiConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiProxyServiceExternalApiTest {
    @Test
    void createsExternalImageEditJobWithMultipartPayloadAndReadsResult() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/edits", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] responseBytes = """
                    {"data":[{"b64_json":"ZmFrZS1pbWFnZQ=="}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            UserApiConfigService userApiConfigService = new UserApiConfigService(
                    mock(UserApiConfigMapper.class),
                    "http://127.0.0.1:%d".formatted(server.getAddress().getPort()),
                    "sk-test",
                    "gpt-image-2",
                    "gpt-5.4-mini"
            );
            AssetService assetService = new AssetService(null, null, new ArtisanProperties(
                    new ArtisanProperties.Auth("test-secret-at-least-long-enough", 7),
                    new ArtisanProperties.Canvas(UUID.randomUUID()),
                    new ArtisanProperties.Cors("*"),
                    new ArtisanProperties.Minio("http://localhost:9000", "minio", "minio123", "bucket"),
                    new ArtisanProperties.Rabbitmq("canvas-save", "canvas-save-dlq"),
                    new ArtisanProperties.ExternalImages("https://example.com/api/images", "", 30),
                    new ArtisanProperties.ExternalApi(true, "X-Livart-Api-Key", "test-key")
            ));
            ExternalImageResultService externalImageResultService = mock(ExternalImageResultService.class);
            when(externalImageResultService.persistJobResult(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()
            ))
                    .thenReturn(List.of(new ExternalImageResultService.StoredImage(
                            UUID.randomUUID(),
                            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                            0,
                            "/api/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/content",
                            "/api/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/preview",
                            "/api/assets/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/thumbnail",
                            "external-job-01.png",
                            "image/png",
                            128L,
                            1024,
                            1024,
                            OffsetDateTime.now()
                    )));
            AiProxyService service = new AiProxyService(
                    userApiConfigService,
                    assetService,
                    objectMapper,
                    new ImageJobEventBroadcaster(objectMapper),
                    mock(SpringAiTextService.class),
                    externalImageResultService,
                    true,
                    2048,
                    1
            );
            UUID ownerId = UUID.randomUUID();

            Map<String, Object> job = service.createExternalImageEditJob(ownerId, new ExternalAiImageDtos.ImageToImageRequest(
                    "把图片里的鞋子换成红色",
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7Z0ioAAAAASUVORK5CYII=",
                    null,
                    List.of(),
                    "9:16",
                    "2k",
                    false
            ));

            Map<String, Object> snapshot = waitForCompletion(service, ownerId, String.valueOf(job.get("jobId")));

            assertThat(authorization.get()).isEqualTo("Bearer sk-test");
            assertThat(contentType.get()).contains("multipart/form-data; boundary=");
            assertThat(body.get()).contains("name=\"model\"", "gpt-image-2", "name=\"prompt\"", "name=\"size\"", "1152x2048");
            assertThat(snapshot.get("status")).isEqualTo("completed");
            assertThat(snapshot).containsKey("response");
            assertThat(snapshot).containsKey("storedImages");
            assertThat((List<?>) snapshot.get("storedImages")).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToStoredExternalImagesWhenInMemoryJobExpired() {
        ObjectMapper objectMapper = new ObjectMapper();
        UserApiConfigService userApiConfigService = new UserApiConfigService(
                mock(UserApiConfigMapper.class),
                "http://127.0.0.1:9999",
                "sk-test",
                "gpt-image-2",
                "gpt-5.4-mini"
        );
        AssetService assetService = new AssetService(null, null, new ArtisanProperties(
                new ArtisanProperties.Auth("test-secret-at-least-long-enough", 7),
                new ArtisanProperties.Canvas(UUID.randomUUID()),
                new ArtisanProperties.Cors("*"),
                new ArtisanProperties.Minio("http://localhost:9000", "minio", "minio123", "bucket"),
                new ArtisanProperties.Rabbitmq("canvas-save", "canvas-save-dlq"),
                new ArtisanProperties.ExternalImages("https://example.com/api/images", "", 30),
                new ArtisanProperties.ExternalApi(true, "X-Livart-Api-Key", "test-key")
        ));
        ExternalImageResultService externalImageResultService = mock(ExternalImageResultService.class);
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(externalImageResultService.listStoredImages(ownerId, jobId))
                .thenReturn(List.of(new ExternalImageResultService.StoredImage(
                        UUID.randomUUID(),
                        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                        0,
                        "/api/assets/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/content",
                        "/api/assets/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/preview",
                        "/api/assets/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/thumbnail",
                        "external-job-01.png",
                        "image/png",
                        256L,
                        1536,
                        1024,
                        OffsetDateTime.now()
                )));
        AiProxyService service = new AiProxyService(
                userApiConfigService,
                assetService,
                objectMapper,
                new ImageJobEventBroadcaster(objectMapper),
                mock(SpringAiTextService.class),
                externalImageResultService,
                true,
                2048,
                1
        );

        Map<String, Object> snapshot = service.getExternalImageJobSnapshot(ownerId, jobId.toString());

        assertThat(snapshot.get("jobId")).isEqualTo(jobId.toString());
        assertThat(snapshot.get("status")).isEqualTo("completed");
        assertThat((List<?>) snapshot.get("storedImages")).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> waitForCompletion(AiProxyService service, UUID ownerId, String jobId) throws Exception {
        for (int index = 0; index < 30; index += 1) {
            Map<String, Object> snapshot = service.getExternalImageJobSnapshot(ownerId, jobId);
            if ("completed".equals(snapshot.get("status")) || "error".equals(snapshot.get("status"))) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("job did not finish in time");
    }
}
