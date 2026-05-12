package com.dxh.spotifysync.modules.sync.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({SpotifySyncProperties.class, NeteaseSyncProperties.class})
public class MusicSyncConfig {
}
