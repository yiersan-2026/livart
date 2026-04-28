package com.artisanlab.skill;

import com.artisanlab.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class ExternalSkillController {
    private final ExternalSkillService externalSkillService;

    public ExternalSkillController(ExternalSkillService externalSkillService) {
        this.externalSkillService = externalSkillService;
    }

    @GetMapping
    public ApiResponse<List<ExternalSkillDtos.SkillSummary>> listSkills() {
        return ApiResponse.ok(externalSkillService.listEnabledSkills());
    }
}
