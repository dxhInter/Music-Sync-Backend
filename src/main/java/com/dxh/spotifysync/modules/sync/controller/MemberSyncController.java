package com.dxh.spotifysync.modules.sync.controller;

import com.dxh.spotifysync.common.api.CommonResult;
import com.dxh.spotifysync.modules.sync.service.SpotifySyncService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Api(tags = "MemberSyncController")
@RequestMapping("/member")
public class MemberSyncController {

    private final SpotifySyncService spotifySyncService;

    public MemberSyncController(SpotifySyncService spotifySyncService) {
        this.spotifySyncService = spotifySyncService;
    }

    @ApiOperation("获取当前用户同步统计")
    @GetMapping("/stats")
    public CommonResult<Map<String, Object>> stats() {
        return CommonResult.success(spotifySyncService.computeStats());
    }

    @ApiOperation("保存当前用户网易云 Cookie")
    @PostMapping("/cookie")
    public CommonResult<Map<String, String>> saveCookie(@RequestBody Map<String, String> body) {
        String cookie = body.get("cookie");
        if (cookie == null || cookie.trim().isEmpty()) {
            return CommonResult.failed("Cookie 不能为空");
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("message", "Cookie 已保存");
        return CommonResult.success(data);
    }

    @ApiOperation("验证网易云 Cookie")
    @PostMapping("/cookie/validate")
    public CommonResult<Map<String, Object>> validateCookie(@RequestBody Map<String, String> body) {
        String cookie = body.get("cookie");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", cookie != null && !cookie.trim().isEmpty());
        return CommonResult.success(data);
    }
}
