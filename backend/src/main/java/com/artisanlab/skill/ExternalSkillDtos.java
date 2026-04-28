package com.artisanlab.skill;

import java.util.List;

public final class ExternalSkillDtos {
    private ExternalSkillDtos() {
    }

    public record SkillSummary(
            String id,
            String name,
            String version,
            String description,
            String sourceUrl,
            String license,
            List<String> supportedTools
    ) {
    }

    public record SkillDefinition(
            String id,
            String name,
            String version,
            String description,
            String sourceUrl,
            String license,
            boolean enabled,
            List<String> supportedTools,
            String promptGuidance
    ) {
        public SkillDefinition {
            supportedTools = supportedTools == null ? List.of() : List.copyOf(supportedTools);
        }

        SkillSummary toSummary() {
            return new SkillSummary(
                    id,
                    name,
                    version,
                    description,
                    sourceUrl,
                    license,
                    supportedTools
            );
        }
    }
}
