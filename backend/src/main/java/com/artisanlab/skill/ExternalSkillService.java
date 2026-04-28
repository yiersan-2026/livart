package com.artisanlab.skill;

import com.artisanlab.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ExternalSkillService {
    private static final Logger log = LoggerFactory.getLogger(ExternalSkillService.class);
    private static final String SKILL_RESOURCE_PATTERN = "classpath*:skills/*/livart-skill.json";

    private final ResourcePatternResolver resourcePatternResolver;
    private final ObjectMapper objectMapper;
    private final String externalSkillDirectory;
    private volatile Map<String, ExternalSkillDtos.SkillDefinition> skillsById = Map.of();

    public ExternalSkillService(
            ResourcePatternResolver resourcePatternResolver,
            ObjectMapper objectMapper,
            @Value("${artisan.external-skills.directory:}") String externalSkillDirectory
    ) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.objectMapper = objectMapper;
        this.externalSkillDirectory = externalSkillDirectory == null ? "" : externalSkillDirectory.trim();
    }

    @PostConstruct
    public void loadInstalledSkills() {
        Map<String, ExternalSkillDtos.SkillDefinition> loadedSkills = new LinkedHashMap<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources(SKILL_RESOURCE_PATTERN);
            for (Resource resource : resources) {
                ExternalSkillDtos.SkillDefinition skill = readSkill(resource);
                putUsableSkill(loadedSkills, skill, resource.getFilename());
            }
            loadFileSystemSkills(loadedSkills);
        } catch (IOException exception) {
            throw new IllegalStateException("load external skills failed", exception);
        }

        skillsById = Map.copyOf(loadedSkills);
        log.info("[external-skill] loaded count={}", skillsById.size());
    }

    public List<ExternalSkillDtos.SkillSummary> listEnabledSkills() {
        return skillsById.values().stream()
                .filter(ExternalSkillDtos.SkillDefinition::enabled)
                .sorted(Comparator.comparing(ExternalSkillDtos.SkillDefinition::name))
                .map(ExternalSkillDtos.SkillDefinition::toSummary)
                .toList();
    }

    public Optional<ExternalSkillDtos.SkillDefinition> findEnabledSkill(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        if (normalizedSkillId.isBlank()) {
            return Optional.empty();
        }
        ExternalSkillDtos.SkillDefinition skill = skillsById.get(normalizedSkillId);
        if (skill == null || !skill.enabled()) {
            return Optional.empty();
        }
        return Optional.of(skill);
    }

    public String requirePromptGuidance(String skillId) {
        String normalizedSkillId = normalizeSkillId(skillId);
        if (normalizedSkillId.isBlank()) {
            return "";
        }
        ExternalSkillDtos.SkillDefinition skill = findEnabledSkill(normalizedSkillId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_SKILL_NOT_FOUND", "外部 Skill 不存在或未启用"));
        String guidance = skill.promptGuidance() == null ? "" : skill.promptGuidance().trim();
        if (guidance.isBlank()) {
            return "";
        }
        return """
                外部 Skill：%s（%s）
                来源：%s
                Skill 指南：%s
                """.formatted(skill.name(), skill.id(), skill.sourceUrl(), guidance).trim();
    }

    private ExternalSkillDtos.SkillDefinition readSkill(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, ExternalSkillDtos.SkillDefinition.class);
        }
    }

    private void loadFileSystemSkills(Map<String, ExternalSkillDtos.SkillDefinition> loadedSkills) throws IOException {
        if (externalSkillDirectory.isBlank()) {
            return;
        }
        Path directory = Path.of(externalSkillDirectory).toAbsolutePath().normalize();
        if (!Files.exists(directory)) {
            log.debug("[external-skill] directory not found path={}", directory);
            return;
        }
        if (!Files.isDirectory(directory)) {
            log.warn("[external-skill] configured path is not directory path={}", directory);
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> jsonFiles = paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path jsonFile : jsonFiles) {
                ExternalSkillDtos.SkillDefinition skill = objectMapper.readValue(jsonFile.toFile(), ExternalSkillDtos.SkillDefinition.class);
                putUsableSkill(loadedSkills, skill, jsonFile.toString());
            }
        }
    }

    private void putUsableSkill(
            Map<String, ExternalSkillDtos.SkillDefinition> loadedSkills,
            ExternalSkillDtos.SkillDefinition skill,
            String source
    ) {
        if (!isUsableSkill(skill)) {
            log.warn("[external-skill] skip invalid skill source={}", source);
            return;
        }
        loadedSkills.put(skill.id(), skill);
    }

    private boolean isUsableSkill(ExternalSkillDtos.SkillDefinition skill) {
        return skill != null
                && skill.id() != null
                && !skill.id().isBlank()
                && skill.name() != null
                && !skill.name().isBlank();
    }

    private String normalizeSkillId(String skillId) {
        return skillId == null ? "" : skillId.trim();
    }
}
