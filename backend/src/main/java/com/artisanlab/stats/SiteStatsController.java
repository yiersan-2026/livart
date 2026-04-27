package com.artisanlab.stats;

import com.artisanlab.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class SiteStatsController {
    private final SiteStatsService siteStatsService;

    public SiteStatsController(SiteStatsService siteStatsService) {
        this.siteStatsService = siteStatsService;
    }

    @GetMapping("/overview")
    public ApiResponse<SiteStatsDtos.Overview> overview() {
        return ApiResponse.ok(siteStatsService.overview());
    }
}
