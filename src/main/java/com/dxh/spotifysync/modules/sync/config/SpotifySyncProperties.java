package com.dxh.spotifysync.modules.sync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "music-sync.spotify")
public class SpotifySyncProperties {

    private boolean enabled = true;

    private String clientId;

    private String clientSecret;

    private String redirectUri;

    private String scope = "user-library-read";

    private long pollIntervalMs = 30000L;

    /**
     * LATEST_ONLY: 首次授权仅记录当前最新时间，不回补历史歌曲
     * ALL: 首次授权时按当前已喜欢歌曲进行回补
     */
    private String initialSyncMode = "LATEST_ONLY";

    private String accountKey = "default";
}
