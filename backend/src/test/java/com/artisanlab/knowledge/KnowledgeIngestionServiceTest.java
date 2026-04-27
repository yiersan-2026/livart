package com.artisanlab.knowledge;

import com.artisanlab.ai.KnowledgeEmbeddingService;
import com.artisanlab.userconfig.UserApiConfigDtos;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {
    @Test
    void fillsAllEmbeddingBatchesAndBackfillsVectorColumn() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                mapper,
                embeddingService,
                resourcePatternResolver,
                true,
                "https://api.example.com/v1",
                "test-key",
                "gpt-5.4-mini",
                1
        );
        KnowledgeChunkEntity firstChunk = chunk("下载与导出", "点击下载按钮导出图片。");
        KnowledgeChunkEntity secondChunk = chunk("去背景", "去背景会保留主体并改成白底。");

        when(embeddingService.hasUsableConfig(any(UserApiConfigDtos.ResolvedConfig.class))).thenReturn(true);
        when(mapper.hasVectorColumn()).thenReturn(true);
        when(mapper.listChunksMissingEmbedding(1))
                .thenReturn(List.of(firstChunk))
                .thenReturn(List.of(secondChunk))
                .thenReturn(List.of());
        when(embeddingService.createEmbedding(any(UserApiConfigDtos.ResolvedConfig.class), eq("下载与导出\n点击下载按钮导出图片。")))
                .thenReturn(List.of(0.1d, 0.2d));
        when(embeddingService.createEmbedding(any(UserApiConfigDtos.ResolvedConfig.class), eq("去背景\n去背景会保留主体并改成白底。")))
                .thenReturn(List.of(0.3d, 0.4d));
        when(mapper.backfillVectorEmbeddingsFromText(1)).thenReturn(1).thenReturn(0);

        KnowledgeIngestionService.KnowledgeEmbeddingFillResult result = service.fillMissingEmbeddings();

        assertThat(result.embeddedChunks()).isEqualTo(2);
        assertThat(result.vectorChunks()).isEqualTo(3);
        assertThat(result.failedChunks()).isZero();
        verify(mapper).updateEmbeddingText(firstChunk.getId(), "[0.1,0.2]");
        verify(mapper).updateVectorEmbedding(firstChunk.getId(), "[0.1,0.2]");
        verify(mapper).updateEmbeddingText(secondChunk.getId(), "[0.3,0.4]");
        verify(mapper).updateVectorEmbedding(secondChunk.getId(), "[0.3,0.4]");
        verify(mapper, times(2)).backfillVectorEmbeddingsFromText(1);
    }

    @Test
    void doesNotLoopForeverWhenOneChunkEmbeddingFails() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        ResourcePatternResolver resourcePatternResolver = mock(ResourcePatternResolver.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                mapper,
                embeddingService,
                resourcePatternResolver,
                true,
                "https://api.example.com/v1",
                "test-key",
                "gpt-5.4-mini",
                2
        );
        KnowledgeChunkEntity failedChunk = chunk("失败片段", "上游暂时失败。");
        KnowledgeChunkEntity secondChunk = chunk("快捷编辑", "快捷编辑走右侧对话链路。");

        when(embeddingService.hasUsableConfig(any(UserApiConfigDtos.ResolvedConfig.class))).thenReturn(true);
        when(mapper.hasVectorColumn()).thenReturn(false);
        when(mapper.listChunksMissingEmbedding(2))
                .thenReturn(List.of(failedChunk, secondChunk))
                .thenReturn(List.of(failedChunk));
        when(embeddingService.createEmbedding(any(UserApiConfigDtos.ResolvedConfig.class), eq("失败片段\n上游暂时失败。")))
                .thenThrow(new IllegalStateException("upstream failed"));
        when(embeddingService.createEmbedding(any(UserApiConfigDtos.ResolvedConfig.class), eq("快捷编辑\n快捷编辑走右侧对话链路。")))
                .thenReturn(List.of(0.5d));

        KnowledgeIngestionService.KnowledgeEmbeddingFillResult result = service.fillMissingEmbeddings();

        assertThat(result.embeddedChunks()).isEqualTo(1);
        assertThat(result.vectorChunks()).isZero();
        assertThat(result.failedChunks()).isEqualTo(1);
        verify(mapper, times(2)).listChunksMissingEmbedding(2);
        verify(mapper).updateEmbeddingText(secondChunk.getId(), "[0.5]");
    }

    private KnowledgeChunkEntity chunk(String title, String content) {
        KnowledgeChunkEntity chunk = new KnowledgeChunkEntity();
        chunk.setId(UUID.randomUUID());
        chunk.setTitle(title);
        chunk.setContent(content);
        return chunk;
    }
}
