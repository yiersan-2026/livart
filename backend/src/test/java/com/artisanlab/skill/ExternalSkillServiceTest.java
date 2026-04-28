package com.artisanlab.skill;

import com.artisanlab.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSkillServiceTest {
    @Test
    void loadsClasspathSkillAndReturnsPromptGuidance() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenReturn(new Resource[]{
                jsonResource("gpt-image.json", """
                        {
                          "id": "gpt-image",
                          "name": "GPT Image 2",
                          "version": "1.0.0",
                          "description": "图片生成 Skill",
                          "sourceUrl": "https://github.com/wuyoscar/gpt_image_2_skill",
                          "license": "CC BY 4.0",
                          "enabled": true,
                          "supportedTools": ["tool.image.generate"],
                          "promptGuidance": "保留用户目标并优化图片提示词"
                        }
                        """)
        });
        ExternalSkillService service = new ExternalSkillService(resolver, new ObjectMapper(), "");

        service.loadInstalledSkills();

        assertThat(service.listEnabledSkills())
                .extracting(ExternalSkillDtos.SkillSummary::id)
                .containsExactly("gpt-image");
        assertThat(service.requirePromptGuidance("gpt-image"))
                .contains("GPT Image 2", "保留用户目标并优化图片提示词");
    }

    @Test
    void loadsFileSystemSkillAndOverridesSameId(@TempDir Path tempDir) throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenReturn(new Resource[]{
                jsonResource("skill.json", """
                        {
                          "id": "image-skill",
                          "name": "Classpath Skill",
                          "version": "1.0.0",
                          "description": "",
                          "sourceUrl": "",
                          "license": "",
                          "enabled": true,
                          "supportedTools": ["tool.image.generate"],
                          "promptGuidance": "classpath guidance"
                        }
                        """)
        });
        Files.writeString(tempDir.resolve("image-skill.json"), """
                {
                  "id": "image-skill",
                  "name": "Filesystem Skill",
                  "version": "2.0.0",
                  "description": "",
                  "sourceUrl": "",
                  "license": "",
                  "enabled": true,
                  "supportedTools": ["tool.image.edit"],
                  "promptGuidance": "filesystem guidance"
                }
                """);
        ExternalSkillService service = new ExternalSkillService(resolver, new ObjectMapper(), tempDir.toString());

        service.loadInstalledSkills();

        assertThat(service.listEnabledSkills())
                .extracting(ExternalSkillDtos.SkillSummary::name)
                .containsExactly("Filesystem Skill");
        assertThat(service.requirePromptGuidance("image-skill")).contains("filesystem guidance");
    }

    @Test
    void rejectsUnknownSkill() throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenReturn(new Resource[0]);
        ExternalSkillService service = new ExternalSkillService(resolver, new ObjectMapper(), "");

        service.loadInstalledSkills();

        assertThatThrownBy(() -> service.requirePromptGuidance("missing"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("外部 Skill 不存在或未启用");
    }

    private static Resource jsonResource(String filename, String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
