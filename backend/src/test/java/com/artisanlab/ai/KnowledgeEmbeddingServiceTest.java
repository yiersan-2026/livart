package com.artisanlab.ai;

import com.artisanlab.userconfig.UserApiConfigDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeEmbeddingServiceTest {
    @Test
    void requiresExternalEmbeddingConfig() {
        KnowledgeEmbeddingService service = new KnowledgeEmbeddingService(
                new ObjectMapper(),
                "BAAI/bge-m3",
                "",
                ""
        );
        UserApiConfigDtos.ResolvedConfig emptyConfig = new UserApiConfigDtos.ResolvedConfig(
                "",
                "",
                "",
                "",
                "",
                "",
                true
        );

        assertThat(service.hasUsableConfig(emptyConfig)).isFalse();
        assertThatThrownBy(() -> service.createEmbedding(emptyConfig, "怎么导出图片？"))
                .hasMessageContaining("知识库向量生成配置缺失");
    }

    @Test
    void vectorLiteralKeepsFiniteValuesOnly() {
        assertThat(KnowledgeEmbeddingService.toVectorLiteral(List.of(0.1d, Double.NaN, 0.2d, Double.POSITIVE_INFINITY)))
                .isEqualTo("[0.1,0.2]");
    }
}
