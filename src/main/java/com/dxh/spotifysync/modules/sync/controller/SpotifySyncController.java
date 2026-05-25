package com.dxh.spotifysync.modules.sync.controller;

import com.dxh.spotifysync.common.api.CommonResult;
import com.dxh.spotifysync.modules.sync.service.SpotifySyncService;
import com.dxh.spotifysync.modules.sync.service.netease.NeteaseMusicClient;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Api(tags = "SpotifySyncController")
@Tag(name = "SpotifySyncController", description = "Spotify 喜欢歌曲同步")
@RequestMapping("/sync/spotify")
public class SpotifySyncController {

    private final SpotifySyncService spotifySyncService;
    private final NeteaseMusicClient neteaseMusicClient;

    public SpotifySyncController(SpotifySyncService spotifySyncService,
                                 NeteaseMusicClient neteaseMusicClient) {
        this.spotifySyncService = spotifySyncService;
        this.neteaseMusicClient = neteaseMusicClient;
    }

    @ApiOperation("获取 Spotify 授权地址")
    @GetMapping("/authorize-url")
    public CommonResult<Map<String, String>> authorizeUrl() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Map<String, String> data = new HashMap<>();
        data.put("authorizeUrl", spotifySyncService.buildAuthorizeUrl(userId));
        return CommonResult.success(data);
    }

    @ApiOperation("Spotify OAuth 回调")
    @GetMapping("/callback")
    public CommonResult<Map<String, Object>> callback(@RequestParam("code") String code,
                                                      @RequestParam("state") String state) {
        return CommonResult.success(spotifySyncService.handleCallback(code, state));
    }

    @ApiOperation("手动执行一次同步")
    @PostMapping("/sync")
    public CommonResult<Map<String, Object>> sync() {
        return CommonResult.success(spotifySyncService.syncLikedTracks());
    }

    @ApiOperation("查看同步状态")
    @GetMapping("/status")
    public CommonResult<Map<String, Object>> status() {
        return CommonResult.success(spotifySyncService.getStatus());
    }

    @ApiOperation("公开同步歌单展示墙")
    @GetMapping("/gallery")
    public CommonResult<List<Map<String, Object>>> gallery(@RequestParam(defaultValue = "20") int limit) {
        return CommonResult.success(spotifySyncService.getGallery(limit));
    }

    @ApiOperation("补全历史歌曲的专辑封面")
    @PostMapping("/backfill-covers")
    public CommonResult<Map<String, Object>> backfillCovers() {
        return CommonResult.success(spotifySyncService.backfillCovers());
    }

    @ApiOperation("检查网易云中转服务和登录状态")
    @GetMapping("/netease/probe")
    public CommonResult<Map<String, Object>> probeNetease() {
        return CommonResult.success(neteaseMusicClient.probe());
    }

    @ApiOperation("按关键词测试网易云搜歌")
    @GetMapping("/netease/search")
    public CommonResult<List<NeteaseMusicClient.SearchSongResult>> searchNetease(@RequestParam("keywords") String keywords) {
        return CommonResult.success(neteaseMusicClient.searchSongs(keywords));
    }
}
