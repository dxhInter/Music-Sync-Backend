package com.dxh.spotifysync.modules.sync.job;

import com.dxh.spotifysync.modules.sync.config.NeteaseSyncProperties;
import com.dxh.spotifysync.modules.sync.config.SpotifySyncProperties;
import com.dxh.spotifysync.modules.sync.service.SpotifySyncService;
import com.dxh.spotifysync.modules.sync.service.netease.NeteaseMusicClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Component
public class SpotifySyncScheduler {

    private final SpotifySyncService spotifySyncService;
    private final SpotifySyncProperties spotifySyncProperties;
    private final NeteaseSyncProperties neteaseSyncProperties;
    private final NeteaseMusicClient neteaseMusicClient;
    @Value("${music-sync.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    public SpotifySyncScheduler(SpotifySyncService spotifySyncService,
                                SpotifySyncProperties spotifySyncProperties,
                                NeteaseSyncProperties neteaseSyncProperties,
                                NeteaseMusicClient neteaseMusicClient) {
        this.spotifySyncService = spotifySyncService;
        this.spotifySyncProperties = spotifySyncProperties;
        this.neteaseSyncProperties = neteaseSyncProperties;
        this.neteaseMusicClient = neteaseMusicClient;
    }

    @Scheduled(fixedDelayString = "${music-sync.spotify.poll-interval-ms:30000}")
    public void syncLikedTracks() {
        if (!schedulerEnabled || !spotifySyncProperties.isEnabled() || !neteaseSyncProperties.isEnabled() || !neteaseMusicClient.isEnabled()) {
            return;
        }
        try {
            spotifySyncService.syncLikedTracks();
        } catch (Exception e) {
            log.warn("定时同步执行失败: {}", e.getMessage());
        }
    }
}
