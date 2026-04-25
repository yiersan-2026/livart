package com.artisanlab.auth;

import com.artisanlab.common.ApiException;
import com.artisanlab.config.ArtisanProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final SecretKeySpec signingKey;
    private final Duration tokenTtl;

    public JwtService(ArtisanProperties properties, ObjectMapper objectMapper) {
        byte[] secretBytes = properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        if (properties.auth().jwtTtlDays() <= 0) {
            throw new IllegalStateException("JWT_TTL_DAYS must be greater than 0");
        }

        this.objectMapper = objectMapper;
        this.signingKey = new SecretKeySpec(secretBytes, "HmacSHA256");
        this.tokenTtl = Duration.ofDays(properties.auth().jwtTtlDays());
    }

    public AuthDtos.AuthResponse issueToken(AuthDtos.AuthUser user) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(tokenTtl);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.id().toString());
        claims.put("username", user.username());
        claims.put("displayName", user.displayName());
        claims.put("iat", now.toEpochSecond());
        claims.put("exp", expiresAt.toEpochSecond());

        String encodedHeader = encodeJson(header);
        String encodedClaims = encodeJson(claims);
        String unsignedToken = encodedHeader + "." + encodedClaims;
        String signature = sign(unsignedToken);

        return new AuthDtos.AuthResponse(user, unsignedToken + "." + signature, expiresAt);
    }

    public UUID verifyAndReadUserId(String token) {
        Map<String, Object> claims = verifyAndReadClaims(token);
        Object subject = claims.get("sub");
        if (!(subject instanceof String subjectText) || subjectText.isBlank()) {
            throw invalidToken();
        }
        try {
            return UUID.fromString(subjectText);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private Map<String, Object> verifyAndReadClaims(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw invalidToken();
        }

        String unsignedToken = parts[0] + "." + parts[1];
        byte[] expected = sign(unsignedToken).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw invalidToken();
        }

        try {
            Map<String, Object> claims = objectMapper.readValue(URL_DECODER.decode(parts[1]), CLAIMS_TYPE);
            Object expiration = claims.get("exp");
            if (!(expiration instanceof Number expirationNumber)) {
                throw invalidToken();
            }
            if (expirationNumber.longValue() <= OffsetDateTime.now().toEpochSecond()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "JWT_EXPIRED", "登录状态已过期，请重新登录");
            }
            return claims;
        } catch (IllegalArgumentException | IOException exception) {
            throw invalidToken();
        }
    }

    private String encodeJson(Map<String, Object> payload) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode JWT payload", exception);
        }
    }

    private String sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            return URL_ENCODER.encodeToString(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign JWT token", exception);
        }
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录状态已失效，请重新登录");
    }
}
