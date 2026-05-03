package com.artisanlab.ai;

import com.artisanlab.memory.UserMemoryEntity;
import com.artisanlab.memory.UserMemoryMapper;
import com.artisanlab.memory.UserMemorySearchResult;
import com.artisanlab.userconfig.UserApiConfigDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryServiceTest {
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
    void buildRelevantMemoryContextMergesStickyAndSemanticMemories() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        UserMemoryService service = new UserMemoryService(
                new ObjectMapper(),
                mapper,
                embeddingService,
                textService,
                4,
                6,
                0.35d
        );
        UUID userId = UUID.randomUUID();

        when(embeddingService.embeddingModel()).thenReturn("BAAI/bge-m3");
        when(mapper.findCachedQueryEmbedding(eq("BAAI/bge-m3"), anyString())).thenReturn("[0.12,0.34]");
        when(mapper.searchByVector(eq(userId), eq("[0.12,0.34]"), eq(4))).thenReturn(List.of(
                new UserMemorySearchResult(
                        UUID.randomUUID().toString(),
                        "visual_style_preference",
                        "视觉偏好",
                        "偏好简洁优雅、编辑感极简和高级留白",
                        "简洁优雅、编辑感极简、高级留白",
                        "high",
                        90,
                        true,
                        0.92d
                )
        ));
        when(mapper.listRecentMemories(eq(userId), eq(6))).thenReturn(List.of(
                new UserMemorySearchResult(
                        UUID.randomUUID().toString(),
                        "aspect_ratio_preference",
                        "画幅偏好",
                        "默认更偏好 9:16 竖版画幅",
                        "9:16",
                        "high",
                        80,
                        true,
                        0.0d
                )
        ));

        String context = service.buildRelevantMemoryContext(userId, CONFIG, "继续生成贴纸详情图");

        assertThat(context)
                .contains("用户长期记忆")
                .contains("视觉偏好")
                .contains("简洁优雅")
                .contains("画幅偏好")
                .contains("9:16");
        verify(embeddingService).embeddingModel();
        verify(mapper).touchCachedQueryEmbedding(eq("BAAI/bge-m3"), anyString());
    }

    @Test
    void buildRelevantMemoryContextPrefersRedisCachedEmbedding() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        RedisEmbeddingCacheService redisEmbeddingCacheService = mock(RedisEmbeddingCacheService.class);
        UserMemoryService service = new UserMemoryService(
                new ObjectMapper(),
                mapper,
                embeddingService,
                textService,
                redisEmbeddingCacheService,
                null,
                4,
                6,
                0.35d
        );
        UUID userId = UUID.randomUUID();

        when(embeddingService.embeddingModel()).thenReturn("BAAI/bge-m3");
        when(redisEmbeddingCacheService.find(eq("user-memory-query"), eq("BAAI/bge-m3"), anyString())).thenReturn("[0.22,0.44]");
        when(mapper.searchByVector(eq(userId), eq("[0.22,0.44]"), eq(4))).thenReturn(List.of(
                new UserMemorySearchResult(
                        UUID.randomUUID().toString(),
                        "layout_preference",
                        "版式偏好",
                        "偏好杂志排版、留白充足",
                        "杂志排版、留白充足",
                        "high",
                        85,
                        true,
                        0.88d
                )
        ));
        when(mapper.listRecentMemories(eq(userId), eq(6))).thenReturn(List.of());

        String context = service.buildRelevantMemoryContext(userId, CONFIG, "继续生成商品详情图");

        assertThat(context).contains("版式偏好").contains("杂志排版");
        verify(embeddingService, never()).createEmbedding(any(), anyString());
        verify(mapper, never()).findCachedQueryEmbedding(anyString(), anyString());
        verify(redisEmbeddingCacheService).find(eq("user-memory-query"), eq("BAAI/bge-m3"), anyString());
    }

    @Test
    void captureAgentTurnExtractsAndUpsertsDurableMemories() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
        SpringAiTextService textService = mock(SpringAiTextService.class);
        UserMemoryService service = new UserMemoryService(
                new ObjectMapper(),
                mapper,
                embeddingService,
                textService,
                4,
                6,
                0.35d
        );
        UUID userId = UUID.randomUUID();

        when(textService.completeText(
                eq(CONFIG),
                anyString(),
                anyString(),
                any(),
                eq("user-memory-capture")
        )).thenReturn("""
                {"memories":[
                  {"slotKey":"business_context","title":"业务背景","summary":"用户主要在做 AI/科技桌搭贴纸商品","value":"AI/科技桌搭贴纸商品","confidence":"high","importance":88,"sticky":true},
                  {"slotKey":"visual_style_preference","title":"视觉偏好","summary":"偏好简洁优雅、编辑感极简和高级留白","value":"简洁优雅、编辑感极简、高级留白","confidence":"high","importance":92,"sticky":true}
                ]}
                """);
        when(embeddingService.hasUsableConfig(CONFIG)).thenReturn(true);
        when(embeddingService.createEmbedding(eq(CONFIG), anyString())).thenReturn(List.of(0.1d, 0.2d));

        service.captureAgentTurn(
                userId,
                CONFIG,
                new UserMemoryService.AgentMemoryCaptureRequest(
                        "我卖 AI 笔记本贴纸，以后详情图默认更简洁优雅。",
                        "",
                        "auto",
                        "2k",
                        "",
                        ""
                )
        );

        ArgumentCaptor<UserMemoryEntity> captor = ArgumentCaptor.forClass(UserMemoryEntity.class);
        verify(mapper, times(2)).upsertMemory(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(UserMemoryEntity::getSlotKey)
                .containsExactlyInAnyOrder("business_context", "visual_style_preference");
        assertThat(captor.getAllValues())
                .extracting(UserMemoryEntity::getSticky)
                .containsOnly(true);
        assertThat(captor.getAllValues())
                .extracting(UserMemoryEntity::getEmbeddingText)
                .allMatch(value -> value != null && value.startsWith("[0.1,0.2]"));
    }
}
