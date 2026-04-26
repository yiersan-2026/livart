package com.artisanlab.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DevHttpLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DevHttpLoggingFilter.class);

    private static final int MAX_CACHED_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_LOG_BODY_CHARS = 4000;
    private static final int MAX_PART_TEXT_CHARS = 1000;
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"[^\"]*(?:api[-_]?key|password|passwd|secret|token|authorization|jwt)[^\"]*\"\\s*:\\s*\")([^\"]*)(\")"
    );
    private static final Pattern QUERY_SECRET_PATTERN = Pattern.compile(
            "(?i)((?:api[-_]?key|password|passwd|secret|token|authorization|jwt)=)[^&\\s]+"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SK_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9._-]{8,}");
    private static final Pattern LARGE_IMAGE_FIELD_PATTERN = Pattern.compile(
            "(?i)(\"(?:b64_json|image|data|content|previewContent|thumbnailContent|maskData|drawingData|compositeImage)\"\\s*:\\s*\")([^\"]{200,})(\")"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_CACHED_REQUEST_BYTES);

        if (shouldCaptureResponseBody(request)) {
            ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
            Throwable failure = null;
            try {
                filterChain.doFilter(wrappedRequest, wrappedResponse);
            } catch (IOException | ServletException | RuntimeException exception) {
                failure = exception;
                throw exception;
            } finally {
                logExchange(wrappedRequest, wrappedResponse, startedAt, failure);
                wrappedResponse.copyBodyToResponse();
            }
            return;
        }

        Throwable failure = null;
        try {
            filterChain.doFilter(wrappedRequest, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            logExchange(wrappedRequest, response, startedAt, failure);
        }
    }

    private boolean shouldCaptureResponseBody(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(request.getMethod().equalsIgnoreCase("GET") && uri.startsWith("/api/assets/"));
    }

    private void logExchange(
            ContentCachingRequestWrapper request,
            HttpServletResponse response,
            long startedAt,
            Throwable failure
    ) {
        long durationMs = System.currentTimeMillis() - startedAt;
        Map<String, Object> requestPayload = summarizeRequest(request);
        Map<String, Object> responsePayload = summarizeResponse(response);
        if (failure != null) {
            responsePayload.put("exception", failure.getClass().getSimpleName());
            responsePayload.put("exceptionMessage", sanitizeForLog(failure.getMessage()));
        }

        log.info(
                "[dev-api] {} {} status={} duration={}ms request={} response={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs,
                requestPayload,
                responsePayload
        );
    }

    private Map<String, Object> summarizeRequest(ContentCachingRequestWrapper request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            payload.put("query", sanitizeForLog(query));
        }
        payload.put("headers", summarizeHeaders(request));
        Map<String, Object> params = summarizeParameters(request);
        if (!params.isEmpty()) {
            payload.put("params", params);
        }

        String contentType = request.getContentType();
        payload.put("contentType", contentType == null || contentType.isBlank() ? "none" : contentType);
        payload.put("contentLength", request.getContentLengthLong());

        if (isMultipart(contentType)) {
            payload.put("parts", summarizeMultipartParts(request));
            return payload;
        }

        if (isTextContentType(contentType)) {
            payload.put("body", readCachedBody(request.getContentAsByteArray(), request.getCharacterEncoding()));
        } else if (request.getContentLengthLong() > 0) {
            payload.put("body", "<binary body omitted>");
        }
        return payload;
    }

    private Map<String, Object> summarizeResponse(HttpServletResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentType", response.getContentType() == null ? "unknown" : response.getContentType());

        if (response instanceof ContentCachingResponseWrapper wrapper) {
            byte[] body = wrapper.getContentAsByteArray();
            payload.put("bodyBytes", body.length);
            if (isTextContentType(response.getContentType())) {
                payload.put("body", readCachedBody(body, response.getCharacterEncoding()));
            } else if (body.length > 0) {
                payload.put("body", "<binary response omitted>");
            }
            return payload;
        }

        payload.put("body", "<streamed response not captured>");
        return payload;
    }

    private Map<String, Object> summarizeHeaders(HttpServletRequest request) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String headerName : Collections.list(request.getHeaderNames())) {
            String normalizedName = headerName.toLowerCase(Locale.ROOT);
            if (!isUsefulHeader(normalizedName)) {
                continue;
            }
            headers.put(headerName, sanitizeHeaderValue(normalizedName, request.getHeader(headerName)));
        }
        return headers;
    }

    private boolean isUsefulHeader(String normalizedName) {
        return normalizedName.equals(HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT))
                || normalizedName.equals(HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT))
                || normalizedName.equals(HttpHeaders.USER_AGENT.toLowerCase(Locale.ROOT))
                || normalizedName.equals(HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT))
                || normalizedName.equals("x-request-id")
                || normalizedName.equals("x-livart-api-key")
                || normalizedName.equals("x-livart-upstream-url");
    }

    private String sanitizeHeaderValue(String normalizedName, String value) {
        if (normalizedName.contains("authorization") || normalizedName.contains("api-key")) {
            return "***";
        }
        return sanitizeForLog(value);
    }

    private Map<String, Object> summarizeParameters(HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (isSensitiveName(name)) {
                params.put(name, "***");
                return;
            }
            List<String> safeValues = new ArrayList<>();
            for (String value : values) {
                safeValues.add(truncate(sanitizeForLog(value), MAX_PART_TEXT_CHARS));
            }
            params.put(name, safeValues.size() == 1 ? safeValues.get(0) : safeValues);
        });
        return params;
    }

    private List<Map<String, Object>> summarizeMultipartParts(HttpServletRequest request) {
        List<Map<String, Object>> parts = new ArrayList<>();
        try {
            for (Part part : request.getParts()) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("name", part.getName());
                summary.put("contentType", part.getContentType() == null ? "none" : part.getContentType());
                summary.put("size", part.getSize());
                String filename = part.getSubmittedFileName();
                if (filename != null && !filename.isBlank()) {
                    summary.put("filename", sanitizeForLog(filename));
                    summary.put("body", "<file omitted>");
                } else if (isSensitiveName(part.getName())) {
                    summary.put("body", "***");
                } else if (part.getContentType() == null || isTextContentType(part.getContentType())) {
                    summary.put("body", readPartText(part));
                } else {
                    summary.put("body", "<binary part omitted>");
                }
                parts.add(summary);
            }
        } catch (Exception exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", exception.getClass().getSimpleName());
            error.put("message", sanitizeForLog(exception.getMessage()));
            parts.add(error);
        }
        return parts;
    }

    private String readPartText(Part part) {
        try {
            byte[] bytes = part.getInputStream().readNBytes(MAX_PART_TEXT_CHARS + 1);
            return readCachedBody(bytes, StandardCharsets.UTF_8.name());
        } catch (IOException exception) {
            return "<part read failed: %s>".formatted(sanitizeForLog(exception.getMessage()));
        }
    }

    private String readCachedBody(byte[] body, String encoding) {
        if (body == null || body.length == 0) {
            return "";
        }
        Charset charset = charsetOrUtf8(encoding);
        return truncate(sanitizeForLog(new String(body, charset)), MAX_LOG_BODY_CHARS);
    }

    private Charset charsetOrUtf8(String encoding) {
        try {
            if (encoding == null || encoding.isBlank() || StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(encoding)) {
                return StandardCharsets.UTF_8;
            }
            return Charset.forName(encoding);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data");
    }

    private boolean isTextContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        return normalizedContentType.contains("json")
                || normalizedContentType.contains("text")
                || normalizedContentType.contains("xml")
                || normalizedContentType.contains("x-www-form-urlencoded");
    }

    private boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return normalizedName.contains("key")
                || normalizedName.contains("password")
                || normalizedName.contains("passwd")
                || normalizedName.contains("secret")
                || normalizedName.contains("token")
                || normalizedName.contains("authorization")
                || normalizedName.contains("jwt");
    }

    static String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = JSON_SECRET_PATTERN.matcher(value).replaceAll("$1***$3");
        sanitized = QUERY_SECRET_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer ***");
        sanitized = SK_KEY_PATTERN.matcher(sanitized).replaceAll("sk-***");
        sanitized = LARGE_IMAGE_FIELD_PATTERN.matcher(sanitized).replaceAll("$1<large image data omitted>$3");
        return sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...<truncated>";
    }
}
