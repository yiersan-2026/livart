package com.artisanlab.maintenance;

import com.artisanlab.asset.AssetService;
import com.artisanlab.canvas.CanvasMapper;
import com.artisanlab.canvas.CanvasSnapshotMapper;
import com.artisanlab.externalapi.ExternalGeneratedImageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyDataCleanupService {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDataCleanupService.class);

    private final AssetService assetService;
    private final CanvasSnapshotMapper canvasSnapshotMapper;
    private final CanvasMapper canvasMapper;
    private final ExternalGeneratedImageMapper externalGeneratedImageMapper;

    public WeeklyDataCleanupService(
            AssetService assetService,
            CanvasSnapshotMapper canvasSnapshotMapper,
            CanvasMapper canvasMapper,
            ExternalGeneratedImageMapper externalGeneratedImageMapper
    ) {
        this.assetService = assetService;
        this.canvasSnapshotMapper = canvasSnapshotMapper;
        this.canvasMapper = canvasMapper;
        this.externalGeneratedImageMapper = externalGeneratedImageMapper;
    }

    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Shanghai")
    @Transactional
    public void purgeAllUserProjectsWeekly() {
        log.warn("[weekly-cleanup] started");
        int deletedObjects = assetService.purgeAllUserAssetsAndObjects();
        int deletedGeneratedMappings = externalGeneratedImageMapper.deleteAllUserGeneratedImages();
        int deletedSnapshots = canvasSnapshotMapper.deleteAllUserCanvasSnapshots();
        int deletedCanvases = canvasMapper.deleteAllUserCanvases();
        log.warn(
                "[weekly-cleanup] completed deletedCanvases={} deletedSnapshots={} deletedGeneratedMappings={} deletedObjects={}",
                deletedCanvases,
                deletedSnapshots,
                deletedGeneratedMappings,
                deletedObjects
        );
    }
}
