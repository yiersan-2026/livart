package com.artisanlab.ai;

import com.artisanlab.common.ApiException;
import com.artisanlab.userconfig.UserApiConfigDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SpringAiTextService {
    private static final Logger log = LoggerFactory.getLogger(SpringAiTextService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    public String completeText(
            UserApiConfigDtos.ResolvedConfig config,
            String systemPrompt,
            String userPrompt,
            Duration timeout,
            String operationName
    ) {
        long startedAt = System.currentTimeMillis();

        try {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(resolveChatBaseUrl(config.baseUrl()))
                    .apiKey(config.apiKey())
                    .completionsPath(resolveCompletionsPath(config.baseUrl()))
                    .embeddingsPath(resolveEmbeddingsPath(config.baseUrl()))
                    .restClientBuilder(buildRestClientBuilder(timeout))
                    .webClientBuilder(WebClient.builder())
                    .build();

            OpenAiApi.ChatCompletionRequest request = new OpenAiApi.ChatCompletionRequest(
                    List.of(
                            new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                            new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER)
                    ),
                    config.chatModel(),
                    0.2d,
                    true
            );

            String text = extractTextFromChunks(openAiApi.chatCompletionStream(request)
                    .collectList()
                    .block(timeout.plusSeconds(5)));

            log.info(
                    "[spring-ai] operation={} model={} duration={}ms textChars={}",
                    operationName,
                    config.chatModel(),
                    System.currentTimeMillis() - startedAt,
                    text.length()
            );
            return text;
        } catch (RestClientResponseException exception) {
            String detail = previewText(exception.getResponseBodyAsString());
            int statusCode = exception.getStatusCode().value();
            log.warn(
                    "[spring-ai] operation={} upstream status={} duration={}ms body={}",
                    operationName,
                    statusCode,
                    System.currentTimeMillis() - startedAt,
                    detail
            );
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SPRING_AI_UPSTREAM_ERROR",
                    "Spring AI 上游错误：%s（状态 %d）".formatted(detail, statusCode)
            );
        } catch (WebClientResponseException exception) {
            String detail = previewText(exception.getResponseBodyAsString());
            int statusCode = exception.getStatusCode().value();
            log.warn(
                    "[spring-ai] operation={} upstream status={} duration={}ms body={}",
                    operationName,
                    statusCode,
                    System.currentTimeMillis() - startedAt,
                    detail
            );
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SPRING_AI_UPSTREAM_ERROR",
                    "Spring AI 上游错误：%s（状态 %d）".formatted(detail, statusCode)
            );
        } catch (ResourceAccessException exception) {
            boolean timeoutDetected = isTimeoutException(exception);
            HttpStatus status = timeoutDetected ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            String code = timeoutDetected ? "SPRING_AI_TIMEOUT" : "SPRING_AI_RESOURCE_ACCESS_FAILED";
            String message = timeoutDetected
                    ? "Spring AI 调用超时：%s".formatted(safeMessage(exception))
                    : "Spring AI 调用失败：%s".formatted(safeMessage(exception));
            log.warn(
                    "[spring-ai] operation={} duration={}ms resourceError={}",
                    operationName,
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            throw new ApiException(status, code, message);
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error(
                    "[spring-ai] operation={} duration={}ms error={}",
                    operationName,
                    System.currentTimeMillis() - startedAt,
                    safeMessage(exception)
            );
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SPRING_AI_CALL_FAILED",
                    "Spring AI 调用失败：%s".formatted(safeMessage(exception))
            );
        }
    }

    static String resolveChatBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String resolveCompletionsPath(String baseUrl) {
        return hasVersionPath(baseUrl) ? "/chat/completions" : "/v1/chat/completions";
    }

    static String resolveEmbeddingsPath(String baseUrl) {
        return hasVersionPath(baseUrl) ? "/embeddings" : "/v1/embeddings";
    }

    static String extractText(OpenAiApi.ChatCompletion completion) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_EMPTY_TEXT", "未能从 Spring AI 响应中获取文本");
        }

        OpenAiApi.ChatCompletionMessage message = completion.choices().get(0).message();
        if (message == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_EMPTY_TEXT", "未能从 Spring AI 响应中获取文本");
        }

        String content = message.content() == null ? "" : message.content().trim();
        if (!content.isBlank()) {
            return content;
        }

        String refusal = message.refusal() == null ? "" : message.refusal().trim();
        if (!refusal.isBlank()) {
            return refusal;
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_EMPTY_TEXT", "未能从 Spring AI 响应中获取文本");
    }

    static String extractTextFromChunks(List<OpenAiApi.ChatCompletionChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_EMPTY_TEXT", "未能从 Spring AI 流式响应中获取文本");
        }

        List<String> parts = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (OpenAiApi.ChatCompletionChunk chunk : chunks) {
            if (chunk == null || chunk.choices() == null) {
                continue;
            }
            for (OpenAiApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
                OpenAiApi.ChatCompletionMessage delta = choice == null ? null : choice.delta();
                if (delta == null) {
                    continue;
                }
                String content = delta.content() == null ? "" : delta.content();
                if (!content.isBlank()) {
                    parts.add(content);
                }
                String refusal = delta.refusal() == null ? "" : delta.refusal();
                if (!refusal.isBlank()) {
                    refusals.add(refusal);
                }
            }
        }

        String text = String.join("", parts).trim();
        if (!text.isBlank()) {
            return text;
        }

        String refusal = String.join("", refusals).trim();
        if (!refusal.isBlank()) {
            return refusal;
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "SPRING_AI_EMPTY_TEXT", "未能从 Spring AI 流式响应中获取文本");
    }

    private RestClient.Builder buildRestClientBuilder(Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(CONNECT_TIMEOUT.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(timeout.toMillis()));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json");
    }

    private static boolean hasVersionPath(String baseUrl) {
        String normalized = resolveChatBaseUrl(baseUrl);
        if (normalized.isBlank()) {
            return false;
        }

        try {
            String path = new URI(normalized).getPath();
            if (path == null || path.isBlank()) {
                return false;
            }
            String compactPath = path.replaceAll("/+$", "");
            return compactPath.matches(".*/v\\d+$");
        } catch (URISyntaxException exception) {
            return normalized.toLowerCase(Locale.ROOT).matches(".*/v\\d+$");
        }
    }

    private static boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timed out") || normalized.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String previewText(String value) {
        if (value == null || value.isBlank()) {
            return "上游无响应内容";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
