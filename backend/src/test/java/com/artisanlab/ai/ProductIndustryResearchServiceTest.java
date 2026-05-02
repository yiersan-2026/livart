package com.artisanlab.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductIndustryResearchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractOutputTextSupportsResponsesOutputText() throws Exception {
        String text = ProductIndustryResearchService.extractOutputText(
                """
                        {"output_text":"{\\"industry\\":\\"香水香氛\\",\\"visualStyle\\":\\"艺术静物、极简留白\\"}"}
                        """,
                objectMapper
        );

        assertThat(text).contains("香水香氛", "艺术静物");
    }

    @Test
    void formatResearchContextCompactsJsonIntoPromptContext() {
        String context = ProductIndustryResearchService.formatResearchContext(
                """
                        {"industry":"香水香氛","audience":"送礼人群","visualStyle":"艺术静物、极简留白、高级光影","trendStyles":["精品画册感","高级静物大片"],"layoutPatterns":["精品画册式首屏","少量卖点标签"],"featureDisplayMethods":["瓶身反射特写","香调标签分区"],"conversionHooks":["送礼仪式感","高级感拥有欲"],"copywritingAngles":["香调","留香","仪式感"],"propsAndScenes":["玻璃反射","丝绸","花材"],"avoid":["过密文字","低价促销感"],"researchSummary":"香水详情图适合克制文字和高级留白。"}
                        """,
                objectMapper
        );

        assertThat(context)
                .contains("行业/品类：香水香氛")
                .contains("行业视觉风格：艺术静物、极简留白、高级光影")
                .contains("流行趋势风格：精品画册感、高级静物大片")
                .contains("产品特点展示：瓶身反射特写、香调标签分区")
                .contains("购买欲钩子：送礼仪式感、高级感拥有欲")
                .contains("图中文字角度：香调、留香、仪式感")
                .contains("避免事项：过密文字、低价促销感");
    }

    @Test
    void extractSourcesFindsWebSearchSourceUrlsRecursively() throws Exception {
        var sources = ProductIndustryResearchService.extractSources(
                """
                        {
                          "output": [
                            {
                              "type": "web_search_call",
                              "action": {
                                "sources": [
                                  {"title": "小红书详情页趋势", "url": "https://example.com/trend"},
                                  {"name": "竞品视觉参考", "source_url": "https://example.com/competitor"}
                                ]
                              }
                            }
                          ]
                        }
                        """,
                objectMapper
        );

        assertThat(sources)
                .extracting(ProductIndustryResearchService.IndustryResearchSource::url)
                .containsExactly("https://example.com/trend", "https://example.com/competitor");
        assertThat(sources)
                .extracting(ProductIndustryResearchService.IndustryResearchSource::title)
                .containsExactly("小红书详情页趋势", "竞品视觉参考");
    }
}
