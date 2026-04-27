package com.artisanlab.stats;

import org.springframework.stereotype.Service;

@Service
public class SiteStatsService {
    private final SiteStatsMapper siteStatsMapper;

    public SiteStatsService(SiteStatsMapper siteStatsMapper) {
        this.siteStatsMapper = siteStatsMapper;
    }

    public SiteStatsDtos.Overview overview() {
        return new SiteStatsDtos.Overview(
                siteStatsMapper.countUsers(),
                siteStatsMapper.countGeneratedImages()
        );
    }
}
