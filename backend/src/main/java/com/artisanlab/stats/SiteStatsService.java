package com.artisanlab.stats;

import com.artisanlab.ai.AiProxyService;
import org.springframework.stereotype.Service;

@Service
public class SiteStatsService {
    private final SiteStatsMapper siteStatsMapper;
    private final AiProxyService aiProxyService;

    public SiteStatsService(SiteStatsMapper siteStatsMapper, AiProxyService aiProxyService) {
        this.siteStatsMapper = siteStatsMapper;
        this.aiProxyService = aiProxyService;
    }

    public SiteStatsDtos.Overview overview() {
        return new SiteStatsDtos.Overview(
                siteStatsMapper.countUsers(),
                siteStatsMapper.countGeneratedImages(),
                aiProxyService.countActiveImageJobs()
        );
    }
}
