package com.artisanlab.userconfig;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserApiConfigServiceTest {
    @Test
    void getConfigDoesNotExposeSavedApiKey() {
        UUID userId = UUID.randomUUID();
        UserApiConfigMapper mapper = mock(UserApiConfigMapper.class);
        when(mapper.findByUserId(userId)).thenReturn(entity(userId, "https://api.example/v1", "sk-secret"));
        UserApiConfigService service = new UserApiConfigService(
                mapper,
                "",
                "",
                "gpt-image-2",
                "gpt-5.4-mini"
        );

        UserApiConfigDtos.Response response = service.getConfig(userId);
        UserApiConfigDtos.ResolvedConfig resolvedConfig = service.getRequiredConfig(userId);

        assertThat(response.apiKey()).isEmpty();
        assertThat(response.hasApiKey()).isTrue();
        assertThat(resolvedConfig.apiKey()).isEqualTo("sk-secret");
    }

    @Test
    void saveConfigKeepsExistingApiKeyWhenRequestLeavesItBlank() {
        UUID userId = UUID.randomUUID();
        UserApiConfigMapper mapper = mock(UserApiConfigMapper.class);
        UserApiConfigEntity existingEntity = entity(userId, "https://api.example/v1", "sk-existing");
        when(mapper.findByUserId(userId)).thenReturn(existingEntity);
        when(mapper.findByUserId(any(UUID.class))).thenReturn(existingEntity);
        UserApiConfigService service = new UserApiConfigService(
                mapper,
                "",
                "",
                "gpt-image-2",
                "gpt-5.4-mini"
        );

        UserApiConfigDtos.Response response = service.saveConfig(
                userId,
                new UserApiConfigDtos.SaveRequest(
                        "https://api.example/v1",
                        "",
                        "gpt-image-2",
                        "gpt-5.4-mini"
                )
        );
        ArgumentCaptor<UserApiConfigEntity> entityCaptor = ArgumentCaptor.forClass(UserApiConfigEntity.class);
        verify(mapper).upsert(entityCaptor.capture());

        assertThat(entityCaptor.getValue().getApiKey()).isEqualTo("sk-existing");
        assertThat(response.apiKey()).isEmpty();
        assertThat(response.hasApiKey()).isTrue();
    }

    @Test
    void serverDefaultConfigIsMaskedPubliclyAndResolvedInternally() {
        UUID userId = UUID.randomUUID();
        UserApiConfigMapper mapper = mock(UserApiConfigMapper.class);
        when(mapper.findByUserId(userId)).thenReturn(null);
        UserApiConfigService service = new UserApiConfigService(
                mapper,
                "https://api.example/v1",
                "sk-default",
                "gpt-image-2",
                "gpt-5.4-mini"
        );

        UserApiConfigDtos.Response response = service.getConfig(userId);
        UserApiConfigDtos.ResolvedConfig resolvedConfig = service.getRequiredConfig(userId);

        assertThat(response.apiKey()).isEmpty();
        assertThat(response.hasApiKey()).isTrue();
        assertThat(response.serverDefault()).isTrue();
        assertThat(resolvedConfig.apiKey()).isEqualTo("sk-default");
    }

    private static UserApiConfigEntity entity(UUID userId, String baseUrl, String apiKey) {
        UserApiConfigEntity entity = new UserApiConfigEntity();
        entity.setUserId(userId);
        entity.setBaseUrl(baseUrl);
        entity.setApiKey(apiKey);
        entity.setImageModel("gpt-image-2");
        entity.setChatModel("gpt-5.4-mini");
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }
}
