package com.artisanlab.externalapi;

import com.artisanlab.asset.AssetDtos;
import com.artisanlab.asset.AssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalImageResultServiceTest {
    @Test
    void persistsBase64ImagesAsAssetsAndMappings() {
        AssetService assetService = mock(AssetService.class);
        ExternalGeneratedImageMapper mapper = mock(ExternalGeneratedImageMapper.class);
        ExternalImageResultService service = new ExternalImageResultService(assetService, mapper, new ObjectMapper());
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();

        when(mapper.selectByOwnerIdAndJobId(ownerId, jobId)).thenReturn(List.of());
        when(assetService.uploadBytes(any(), any(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(new AssetDtos.AssetResponse(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        null,
                        null,
                        "/api/assets/11111111-1111-1111-1111-111111111111/content",
                        "/api/assets/11111111-1111-1111-1111-111111111111/preview",
                        "/api/assets/11111111-1111-1111-1111-111111111111/thumbnail",
                        "external-job-01.png",
                        "image/png",
                        512L,
                        1024,
                        1024,
                        createdAt
                ));

        List<ExternalImageResultService.StoredImage> storedImages = service.persistJobResult(
                ownerId,
                jobId,
                """
                {"data":[{"b64_json":"ZmFrZS1pbWFnZQ=="}]}
                """.getBytes(),
                "application/json; charset=utf-8"
        );

        assertThat(storedImages).hasSize(1);
        assertThat(storedImages.get(0).assetId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(storedImages.get(0).urlPath()).isEqualTo("/api/assets/11111111-1111-1111-1111-111111111111/content");
        verify(assetService).uploadBytes(any(), any(), anyString(), anyString(), any(byte[].class));
        verify(mapper).insertMapping(any(ExternalGeneratedImageEntity.class));
    }

    @Test
    void reusesExistingStoredImagesWithoutPersistingAgain() {
        AssetService assetService = mock(AssetService.class);
        ExternalGeneratedImageMapper mapper = mock(ExternalGeneratedImageMapper.class);
        ExternalImageResultService service = new ExternalImageResultService(assetService, mapper, new ObjectMapper());
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        ExternalGeneratedImageEntity row = new ExternalGeneratedImageEntity();
        row.setId(UUID.randomUUID());
        row.setJobId(jobId);
        row.setOwnerId(ownerId);
        row.setAssetId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        row.setImageIndex(0);
        row.setCreatedAt(createdAt);
        row.setUrlPath("/api/assets/22222222-2222-2222-2222-222222222222/content");
        row.setOriginalFilename("external-job-01.png");
        row.setMimeType("image/png");
        row.setSizeBytes(256L);
        row.setWidth(1536);
        row.setHeight(1024);

        when(mapper.selectByOwnerIdAndJobId(ownerId, jobId)).thenReturn(List.of(row));

        List<ExternalImageResultService.StoredImage> storedImages = service.persistJobResult(
                ownerId,
                jobId,
                """
                {"data":[{"b64_json":"ZmFrZS1pbWFnZQ=="}]}
                """.getBytes(),
                "application/json; charset=utf-8"
        );

        assertThat(storedImages).hasSize(1);
        assertThat(storedImages.get(0).assetId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        verify(assetService, never()).uploadBytes(any(), any(), anyString(), anyString(), any(byte[].class));
    }
}
