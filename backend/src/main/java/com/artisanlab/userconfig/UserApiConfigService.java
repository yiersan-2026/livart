package com.artisanlab.userconfig;

import com.artisanlab.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class UserApiConfigService {
    private static final Set<String> IMAGE_MODELS = Set.of("gpt-image-2");
    private static final Set<String> CHAT_MODELS = Set.of(
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.3-codex",
            "gpt-5.2"
    );
    private static final Set<String> MASKED_API_KEYS = Set.of(
            "",
            "__server_default__",
            "__configured__",
            "********"
    );

    private final UserApiConfigMapper mapper;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultImageModel;
    private final String defaultChatModel;

    public UserApiConfigService(
            UserApiConfigMapper mapper,
            @Value("${artisan.ai.default-base-url:}") String defaultBaseUrl,
            @Value("${artisan.ai.default-api-key:}") String defaultApiKey,
            @Value("${artisan.ai.default-image-model:gpt-image-2}") String defaultImageModel,
            @Value("${artisan.ai.default-chat-model:gpt-5.5}") String defaultChatModel
    ) {
        this.mapper = mapper;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl;
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey;
        this.defaultImageModel = defaultImageModel == null ? "" : defaultImageModel;
        this.defaultChatModel = defaultChatModel == null ? "" : defaultChatModel;
    }

    @Transactional(readOnly = true)
    public UserApiConfigDtos.Response getConfig(UUID userId) {
        UserApiConfigEntity entity = mapper.findByUserId(userId);
        return entity == null ? getServerDefaultResponse() : toResponse(entity);
    }

    @Transactional(readOnly = true)
    public UserApiConfigDtos.ResolvedConfig getRequiredConfig(UUID userId) {
        UserApiConfigEntity entity = mapper.findByUserId(userId);
        if (entity == null) {
            UserApiConfigDtos.ResolvedConfig defaultConfig = getServerDefaultResolvedConfig();
            if (defaultConfig != null) {
                return defaultConfig;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "USER_API_CONFIG_REQUIRED", "请先配置中转站 Base URL 和 API Key");
        }
        return toResolvedConfig(entity);
    }

    @Transactional
    public UserApiConfigDtos.Response saveConfig(UUID userId, UserApiConfigDtos.SaveRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        String imageModel = normalizeModel(request.model(), IMAGE_MODELS, "生图模型不支持");
        String chatModel = normalizeModel(request.chatModel(), CHAT_MODELS, "对话模型不支持");
        UserApiConfigEntity existingEntity = mapper.findByUserId(userId);

        if (baseUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_API_CONFIG", "中转站 Base URL 不能为空");
        }

        String resolvedApiKey = isMaskedApiKey(apiKey) && existingEntity != null
                ? existingEntity.getApiKey()
                : apiKey;
        if (resolvedApiKey == null || resolvedApiKey.isBlank() || isMaskedApiKey(resolvedApiKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_API_CONFIG", "中转站 Base URL 和 API Key 不能为空");
        }

        UserApiConfigEntity entity = new UserApiConfigEntity();
        entity.setUserId(userId);
        entity.setBaseUrl(baseUrl);
        entity.setApiKey(resolvedApiKey);
        entity.setImageModel(imageModel);
        entity.setChatModel(chatModel);
        mapper.upsert(entity);

        return toResponse(mapper.findByUserId(userId));
    }

    private UserApiConfigDtos.Response toResponse(UserApiConfigEntity entity) {
        return new UserApiConfigDtos.Response(
                entity.getBaseUrl(),
                UserApiConfigDtos.MASKED_API_KEY,
                entity.getImageModel(),
                entity.getChatModel(),
                joinUrl(entity.getBaseUrl(), "images/generations"),
                joinUrl(entity.getBaseUrl(), "images/edits"),
                entity.getUpdatedAt(),
                hasPlainApiKey(entity.getApiKey()),
                false
        );
    }

    private UserApiConfigDtos.Response getServerDefaultResponse() {
        String baseUrl = defaultBaseUrl.trim();
        String apiKey = defaultApiKey.trim();
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return null;
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String imageModel = normalizeModel(
                defaultImageModel.isBlank() ? "gpt-image-2" : defaultImageModel,
                IMAGE_MODELS,
                "默认生图模型不支持"
        );
        String chatModel = normalizeModel(
                defaultChatModel.isBlank() ? "gpt-5.5" : defaultChatModel,
                CHAT_MODELS,
                "默认对话模型不支持"
        );

        return new UserApiConfigDtos.Response(
                normalizedBaseUrl,
                UserApiConfigDtos.MASKED_API_KEY,
                imageModel,
                chatModel,
                joinUrl(normalizedBaseUrl, "images/generations"),
                joinUrl(normalizedBaseUrl, "images/edits"),
                null,
                true,
                true
        );
    }

    private UserApiConfigDtos.ResolvedConfig getServerDefaultResolvedConfig() {
        String baseUrl = defaultBaseUrl.trim();
        String apiKey = defaultApiKey.trim();
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return null;
        }

        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String imageModel = normalizeModel(
                defaultImageModel.isBlank() ? "gpt-image-2" : defaultImageModel,
                IMAGE_MODELS,
                "默认生图模型不支持"
        );
        String chatModel = normalizeModel(
                defaultChatModel.isBlank() ? "gpt-5.5" : defaultChatModel,
                CHAT_MODELS,
                "默认对话模型不支持"
        );

        return new UserApiConfigDtos.ResolvedConfig(
                normalizedBaseUrl,
                apiKey,
                imageModel,
                chatModel,
                joinUrl(normalizedBaseUrl, "images/generations"),
                joinUrl(normalizedBaseUrl, "images/edits"),
                true
        );
    }

    private UserApiConfigDtos.ResolvedConfig toResolvedConfig(UserApiConfigEntity entity) {
        return new UserApiConfigDtos.ResolvedConfig(
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getImageModel(),
                entity.getChatModel(),
                joinUrl(entity.getBaseUrl(), "images/generations"),
                joinUrl(entity.getBaseUrl(), "images/edits"),
                false
        );
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASE_URL", "中转站 Base URL 必须以 http:// 或 https:// 开头");
        }
        return normalized;
    }

    private String normalizeModel(String model, Set<String> allowedModels, String message) {
        String normalized = model == null ? "" : model.trim();
        if (!allowedModels.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MODEL", message);
        }
        return normalized;
    }

    private String joinUrl(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private boolean isMaskedApiKey(String apiKey) {
        return MASKED_API_KEYS.contains(apiKey == null ? "" : apiKey.trim());
    }

    private boolean hasPlainApiKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !isMaskedApiKey(apiKey);
    }
}
