package com.dxh.spotifysync.modules.ums.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dxh.spotifysync.modules.ums.dto.UmsMemberParam;
import com.dxh.spotifysync.modules.ums.model.UmsMember;

/**
 * @Author: dxh
 * @Date: 2026/01/27/10:15
 */
public interface UmsMemberService extends IService<UmsMember> {

    UmsMember getByUsername(String username);

    UmsMember register(UmsMemberParam param, String idempotencyKey);

    String login(String username, String password);

    String refreshToken(String oldToken);

    void logout(Long memberId);
}
