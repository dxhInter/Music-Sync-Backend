package com.dxh.spotifysync.modules.ums.service.impl;

/**
 * @Author: dxh
 * @Date: 2026/01/27/10:16
 */
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.dxh.spotifysync.common.api.ResultCode;
import com.dxh.spotifysync.common.exception.Asserts;
import com.dxh.spotifysync.common.service.RedisService;
import com.dxh.spotifysync.modules.ums.dto.UmsMemberParam;
import com.dxh.spotifysync.modules.ums.mapper.UmsMemberMapper;
import com.dxh.spotifysync.modules.ums.model.UmsMember;
import com.dxh.spotifysync.modules.ums.service.UmsMemberService;
import com.dxh.spotifysync.security.util.JwtTokenUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class UmsMemberServiceImpl extends ServiceImpl<UmsMemberMapper, UmsMember> implements UmsMemberService {

    public static final String MALL_IDEM_REGISTER_LOCK = "mall:idem:register:lock:";
    public static final String MALL_IDEM_REGISTER_RESULT = "mall:idem:register:result:";

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private RedisService redisService;

    private static final String MEMBER_TOKEN_KEY = "mall:member:token:";

    @Override
    public UmsMember getByUsername(String username) {
        QueryWrapper<UmsMember> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(UmsMember::getUsername, username);
        List<UmsMember> list = list(wrapper);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public UmsMember register(UmsMemberParam param, String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey)) {
            Asserts.fail(ResultCode.BAD_REQUEST, "缺少 Idempotency-Key");
        }
        String lockKey = MALL_IDEM_REGISTER_LOCK + idempotencyKey;
        String resultKey = MALL_IDEM_REGISTER_RESULT + idempotencyKey;
        Object cacheResult = redisService.get(resultKey);
        if (cacheResult != null) {
            return (UmsMember) cacheResult;
        }
        Boolean locked = redisService.setIfAbsent(lockKey, "processing", 30);
        if (Boolean.FALSE.equals(locked)) {
            Asserts.fail(ResultCode.CONFLICT, "请求正在处理中，请稍后再试");
        }
        UmsMember member = new UmsMember();
        BeanUtils.copyProperties(param, member);
        member.setCreateTime(new Date());
        member.setStatus(1);

        if (getByUsername(member.getUsername()) != null) {
            Asserts.fail(ResultCode.CONFLICT, "用户名已存在");
        }

        member.setPassword(passwordEncoder.encode(param.getPassword()));
        try {
            save(member);
            // 将结果缓存一段时间，防止重复注册
            redisService.set(resultKey, member, 600);
            return member;
        } catch (Exception e) {
            if (e.getCause() instanceof java.sql.SQLIntegrityConstraintViolationException){
                Asserts.fail(ResultCode.CONFLICT, "用户名已存在");
            } else {
                Asserts.fail("注册失败");
            }
            throw e;
        } finally {
            redisService.del(lockKey);
        }
    }

    @Override
    public String login(String username, String password) {
        UmsMember member = getByUsername(username);
        if (member == null) {
            Asserts.fail(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, member.getPassword())) {
            Asserts.fail(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (member.getStatus() == 0) {
            Asserts.fail(ResultCode.FORBIDDEN, "账号已被禁用");
        }

        String token = jwtTokenUtil.generateToken(member.getId(), member.getUsername());
        redisService.set(MEMBER_TOKEN_KEY + member.getId(), token, jwtTokenUtil.getExpiration());
        return token;
    }

    @Override
    public String refreshToken(String oldToken) {
        if (StrUtil.isEmpty(oldToken)) {
            return null;
        }
        String token = oldToken.substring(jwtTokenUtil.getTokenHead().length());
        Long userId = jwtTokenUtil.getUserIdFromToken(token);
        if (userId == null) {
            Asserts.fail("validate token error");
        }
        String redisKey = MEMBER_TOKEN_KEY + userId;
        String redisToken = (String) redisService.get(redisKey);
        if (redisToken == null || !redisToken.equals(token)) {
            Asserts.fail("token is expired, please login again");
        }
        String newToken = jwtTokenUtil.refreshHeadToken(oldToken);
        if (newToken == null) {
            Asserts.fail("refresh token error");
        }
        redisService.set(redisKey, newToken, jwtTokenUtil.getExpiration());
        return newToken;
    }

    @Override
    public void logout(Long memberId) {
        if (memberId != null) {
            redisService.del(MEMBER_TOKEN_KEY + memberId);
        }
    }
}
