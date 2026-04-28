package com.artisanlab.stats;

import com.artisanlab.ai.AiProxyService;
import org.springframework.stereotype.Service;

@Service
public class SiteStatsService {
    private final SiteStatsMapper siteStatsMapper;
    private final AiProxyService aiProxyService;
    private final SystemResourceStatsService systemResourceStatsService;

    public SiteStatsService(
            SiteStatsMapper siteStatsMapper,
            AiProxyService aiProxyService,
            SystemResourceStatsService systemResourceStatsService
    ) {
        this.siteStatsMapper = siteStatsMapper;
        this.aiProxyService = aiProxyService;
        this.systemResourceStatsService = systemResourceStatsService;
    }

    public SiteStatsDtos.Overview overview() {
        return new SiteStatsDtos.Overview(
                siteStatsMapper.countUsers(),
                siteStatsMapper.countGeneratedImages(),
                aiProxyService.countActiveImageJobs(),
                systemResourceStatsService.memory(),
                systemResourceStatsService.processor(),
                systemResourceStatsService.disk()
        );
    }
}
