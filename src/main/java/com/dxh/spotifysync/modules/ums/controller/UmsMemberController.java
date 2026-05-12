package com.dxh.spotifysync.modules.ums.controller;

import com.dxh.spotifysync.common.api.CommonResult;
import com.dxh.spotifysync.modules.ums.dto.UmsMemberLoginParam;
import com.dxh.spotifysync.modules.ums.dto.UmsMemberParam;
import com.dxh.spotifysync.modules.ums.model.UmsMember;
import com.dxh.spotifysync.modules.ums.service.UmsMemberService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author: dxh
 * @Date: 2026/01/27/10:23
 */

@RestController
@Api(tags = "UmsMemberController")
@Tag(name = "UmsMemberController",description = "前台用户管理")
@RequestMapping("/member")
public class UmsMemberController {

    @Autowired
    private UmsMemberService memberService;

    @Value("${jwt.tokenHead}")
    private String tokenHead;

    @ApiOperation("用户注册")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "Idempotency-Key", value = "幂等性密钥", required = true, dataType = "String", paramType = "header")
    })
    @PostMapping("/register")
    public CommonResult<UmsMember> register(@Valid @RequestBody UmsMemberParam param, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return CommonResult.success(memberService.register(param, idempotencyKey));
    }

    @ApiOperation("用户登录")
    @PostMapping("/login")
    public CommonResult login(@Valid @RequestBody UmsMemberLoginParam param) {
        String token = memberService.login(param.getUsername(), param.getPassword());
        Map<String, String> map = new HashMap<>();
        map.put("token", token);
        map.put("tokenHead", tokenHead);
        return CommonResult.success(map);
    }

    @ApiOperation("获取当前用户信息")
    @GetMapping("/info")
    public CommonResult info(Principal principal) {
        if (principal == null) {
            return CommonResult.unauthorized(null);
        }
        Long memberId = Long.valueOf(principal.getName());
        UmsMember member = memberService.getById(memberId);
        return CommonResult.success(member);
    }

    @ApiOperation("退出登录")
    @PostMapping("/logout")
    public CommonResult logout(Principal principal) {
        if (principal != null) {
            memberService.logout(Long.valueOf(principal.getName()));
        }
        return CommonResult.success(null);
    }
}
