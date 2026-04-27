package com.artisanlab.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiTextServiceTest {
    @Test
    void usesShortChatPathWhenGatewayBaseUrlAlreadyContainsV1() {
        assertThat(SpringAiTextService.resolveCompletionsPath("https://api.sisct2.xyz/v1"))
                .isEqualTo("/chat/completions");
        assertThat(SpringAiTextService.resolveEmbeddingsPath("https://api.sisct2.xyz/v1"))
                .isEqualTo("/embeddings");
    }

    @Test
    void usesDefaultChatPathWhenBaseUrlDoesNotContainVersionSegment() {
        assertThat(SpringAiTextService.resolveCompletionsPath("https://api.openai.com"))
                .isEqualTo("/v1/chat/completions");
        assertThat(SpringAiTextService.resolveEmbeddingsPath("https://api.openai.com"))
                .isEqualTo("/v1/embeddings");
    }

    @Test
    void extractsAssistantContentFromChatCompletion() {
        OpenAiApi.ChatCompletion completion = new OpenAiApi.ChatCompletion(
                "resp-1",
                List.of(new OpenAiApi.ChatCompletion.Choice(
                        null,
                        0,
                        new OpenAiApi.ChatCompletionMessage("优化后的提示词", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT),
                        null
                )),
                1L,
                "gpt-5.4-mini",
                null,
                null,
                "chat.completion",
                null
        );

        assertThat(SpringAiTextService.extractText(completion)).isEqualTo("优化后的提示词");
    }

    @Test
    void fallsBackToRefusalTextWhenContentIsEmpty() {
        OpenAiApi.ChatCompletion completion = new OpenAiApi.ChatCompletion(
                "resp-2",
                List.of(new OpenAiApi.ChatCompletion.Choice(
                        null,
                        0,
                        new OpenAiApi.ChatCompletionMessage(
                                "",
                                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                                null,
                                null,
                                null,
                                "内容被拒绝",
                                null,
                                null
                        ),
                        null
                )),
                1L,
                "gpt-5.4-mini",
                null,
                null,
                "chat.completion",
                null
        );

        assertThat(SpringAiTextService.extractText(completion)).isEqualTo("内容被拒绝");
    }

    @Test
    void extractsAssistantContentFromStreamChunks() {
        List<OpenAiApi.ChatCompletionChunk> chunks = List.of(
                chunk("优化后"),
                chunk("的提示词")
        );

        assertThat(SpringAiTextService.extractTextFromChunks(chunks)).isEqualTo("优化后的提示词");
    }

    @Test
    void throwsWhenChatCompletionHasNoReadableText() {
        OpenAiApi.ChatCompletion completion = new OpenAiApi.ChatCompletion(
                "resp-3",
                List.of(),
                1L,
                "gpt-5.4-mini",
                null,
                null,
                "chat.completion",
                null
        );

        assertThatThrownBy(() -> SpringAiTextService.extractText(completion))
                .hasMessageContaining("未能从 Spring AI 响应中获取文本");
    }

    private static OpenAiApi.ChatCompletionChunk chunk(String content) {
        return new OpenAiApi.ChatCompletionChunk(
                "chunk-1",
                List.of(new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        null,
                        0,
                        new OpenAiApi.ChatCompletionMessage(content, OpenAiApi.ChatCompletionMessage.Role.ASSISTANT),
                        null
                )),
                1L,
                "gpt-5.4-mini",
                null,
                null,
                "chat.completion.chunk",
                null
        );
    }
}
