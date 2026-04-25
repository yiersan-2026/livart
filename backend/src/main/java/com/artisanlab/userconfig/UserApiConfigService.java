package com.artisanlab.userconfig;

import com.artisanlab.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class UserApiConfigService {
    private static final Set<String> IMAGE_MODELS = Set.of("gpt-image-2");
    private static final Set<String> CHAT_MODELS = Set.of("gpt-5.5", "gpt-5.4");

    private final UserApiConfigMapper mapper;

    public UserApiConfigService(UserApiConfigMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public UserApiConfigDtos.Response getConfig(UUID userId) {
        UserApiConfigEntity entity = mapper.findByUserId(userId);
        return entity == null ? null : toResponse(entity);
    }

    @Transactional(readOnly = true)
    public UserApiConfigDtos.Response getRequiredConfig(UUID userId) {
        UserApiConfigEntity entity = mapper.findByUserId(userId);
        if (entity == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USER_API_CONFIG_REQUIRED", "请先配置中转站 Base URL 和 API Key");
        }
        return toResponse(entity);
    }

    @Transactional
    public UserApiConfigDtos.Response saveConfig(UUID userId, UserApiConfigDtos.SaveRequest request) {
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String apiKey = request.apiKey() == null ? "" : request.apiKey().trim();
        String imageModel = normalizeModel(request.model(), IMAGE_MODELS, "生图模型不支持");
        String chatModel = normalizeModel(request.chatModel(), CHAT_MODELS, "对话模型不支持");

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_API_CONFIG", "中转站 Base URL 和 API Key 不能为空");
        }

        UserApiConfigEntity entity = new UserApiConfigEntity();
        entity.setUserId(userId);
        entity.setBaseUrl(baseUrl);
        entity.setApiKey(apiKey);
        entity.setImageModel(imageModel);
        entity.setChatModel(chatModel);
        mapper.upsert(entity);

        return toResponse(mapper.findByUserId(userId));
    }

    private UserApiConfigDtos.Response toResponse(UserApiConfigEntity entity) {
        return new UserApiConfigDtos.Response(
                entity.getBaseUrl(),
                entity.getApiKey(),
                entity.getImageModel(),
                entity.getChatModel(),
                joinUrl(entity.getBaseUrl(), "images/generations"),
                joinUrl(entity.getBaseUrl(), "images/edits"),
                entity.getUpdatedAt()
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
}
