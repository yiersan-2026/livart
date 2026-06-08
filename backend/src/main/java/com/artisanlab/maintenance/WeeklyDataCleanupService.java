package com.artisanlab.maintenance;

import com.artisanlab.asset.AssetService;
import com.artisanlab.canvas.CanvasMapper;
import com.artisanlab.canvas.CanvasSnapshotMapper;
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

    public WeeklyDataCleanupService(
            AssetService assetService,
            CanvasSnapshotMapper canvasSnapshotMapper,
            CanvasMapper canvasMapper
    ) {
        this.assetService = assetService;
        this.canvasSnapshotMapper = canvasSnapshotMapper;
        this.canvasMapper = canvasMapper;
    }

    @Scheduled(cron = "0 0 0 * * MON", zone = "Asia/Shanghai")
    @Transactional
    public void purgeAllUserProjectsWeekly() {
        log.warn("[weekly-cleanup] started");
        int deletedObjects = assetService.purgeAllUserAssetsAndObjects();
        int deletedSnapshots = canvasSnapshotMapper.deleteAllUserCanvasSnapshots();
        int deletedCanvases = canvasMapper.deleteAllUserCanvases();
        log.warn(
                "[weekly-cleanup] completed deletedCanvases={} deletedSnapshots={} deletedObjects={}",
                deletedCanvases,
                deletedSnapshots,
                deletedObjects
        );
    }
}
