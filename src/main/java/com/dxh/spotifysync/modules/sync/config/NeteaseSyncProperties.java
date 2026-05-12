package com.dxh.spotifysync.modules.sync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "music-sync.netease")
public class NeteaseSyncProperties {

    private boolean enabled = false;

    /**
     * 常见实现为本地或远程 NeteaseCloudMusicApi 服务，例如 http://127.0.0.1:3000
     */
    private String baseUrl = "http://127.0.0.1:3000";

    /**
     * 非官方接口通常依赖登录后的 cookie。
     */
    private String cookie;

    private int searchLimit = 10;

    /**
     * 结果分数阈值，0~1。
     */
    private double minScore = 0.75D;
}
