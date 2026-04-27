package com.artisanlab.ai;

import com.artisanlab.knowledge.KnowledgeChunkMapper;
import com.artisanlab.knowledge.KnowledgeSearchResult;
import com.artisanlab.userconfig.UserApiConfigDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAnswerServiceTest {
    private static final UserApiConfigDtos.ResolvedConfig CONFIG = new UserApiConfigDtos.ResolvedConfig(
            "https://api.example.com/v1",
            "test-key",
            "gpt-image-2",
            "gpt-5.4-mini",
            "https://api.example.com/v1/images/generations",
            "https://api.example.com/v1/images/edits",
            true
    );

    @Test
    void answersSystemQuestionWithKnowledgeSnippets() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        KnowledgeAnswerService service = new KnowledgeAnswerService(mapper, embeddingService, textService, 4);

        when(embeddingService.createEmbedding(CONFIG, "怎么导出图片？")).thenReturn(List.of(0.1d, 0.2d));
        when(mapper.searchByVector("[0.1,0.2]", 4)).thenReturn(List.of(
                new KnowledgeSearchResult("chunk-1", "livart-system", "下载与导出", "点击顶部下载按钮；未选择时下载全部成品图片。", 0.92d)
        ));
        when(textService.completeText(eq(CONFIG), anyString(), anyString(), any(), eq("knowledge-answer")))
                .thenReturn("点击顶部下载按钮即可导出图片；如果没有选择图片，会默认下载全部成品图。");

        String answer = service.answerSystemQuestion(CONFIG, "怎么导出图片？", "默认回答");

        assertThat(answer).contains("下载按钮").contains("全部成品图");
        verify(mapper).searchByVector("[0.1,0.2]", 4);
        verify(mapper, never()).searchByText(anyString(), anyInt());
    }

    @Test
    void reusesCachedQueryEmbeddingWhenQuestionWasSeenBefore() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        KnowledgeAnswerService service = new KnowledgeAnswerService(mapper, embeddingService, textService, 4);

        when(embeddingService.embeddingModel()).thenReturn("BAAI/bge-m3");
        when(mapper.findCachedQueryEmbedding(eq("BAAI/bge-m3"), anyString())).thenReturn("[0.3,0.4]");
        when(mapper.searchByVector("[0.3,0.4]", 4)).thenReturn(List.of(
                new KnowledgeSearchResult("chunk-2", "livart-system", "去背景", "点击去背景会保留主体并改成白底。", 0.91d)
        ));
        when(textService.completeText(eq(CONFIG), anyString(), anyString(), any(), eq("knowledge-answer")))
                .thenReturn("点击图片工具条里的去背景即可保留主体并生成白底图片。");

        String answer = service.answerSystemQuestion(CONFIG, " 怎么去背景？ ", "默认回答");

        assertThat(answer).contains("去背景").contains("白底");
        verify(embeddingService, never()).createEmbedding(any(), anyString());
        verify(mapper).touchCachedQueryEmbedding(eq("BAAI/bge-m3"), anyString());
        verify(mapper, never()).upsertCachedQueryEmbedding(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void throwsWhenEmbeddingFails() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        KnowledgeAnswerService service = new KnowledgeAnswerService(mapper, embeddingService, textService, 4);

        when(embeddingService.createEmbedding(CONFIG, "怎么去背景？")).thenThrow(new IllegalStateException("embedding failed"));

        assertThatThrownBy(() -> service.answerSystemQuestion(CONFIG, "怎么去背景？", "默认回答"))
                .hasMessageContaining("embedding failed");

        verify(mapper, never()).searchByText(anyString(), anyInt());
        verify(textService, never()).completeText(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void returnsFallbackAnswerWhenKnowledgeMisses() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        KnowledgeAnswerService service = new KnowledgeAnswerService(mapper, embeddingService, textService, 4);

        when(embeddingService.createEmbedding(CONFIG, "你是谁")).thenReturn(List.of(0.1d));
        when(mapper.searchByVector("[0.1]", 4)).thenReturn(List.of());
        when(mapper.searchByText("你是谁", 4)).thenReturn(List.of());

        String answer = service.answerSystemQuestion(CONFIG, "你是谁", "我是 livart 助手。");

        assertThat(answer).isEqualTo("我是 livart 助手。");
        verify(textService, never()).completeText(any(), anyString(), anyString(), any(), anyString());
    }
}
