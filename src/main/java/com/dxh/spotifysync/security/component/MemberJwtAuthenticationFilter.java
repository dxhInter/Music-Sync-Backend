package com.dxh.spotifysync.security.component;

import cn.hutool.core.util.StrUtil;
import com.dxh.spotifysync.common.service.RedisService;
import com.dxh.spotifysync.security.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * 前台会员 JWT 认证过滤器
 * @author xinhaodu
 * @date 2026/1/27
 */
@Component
public class MemberJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String MEMBER_TOKEN_KEY = "mall:member:token:";

    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private RedisService redisService;
    @Value("${jwt.tokenHeader}")
    private String tokenHeader;
    @Value("${jwt.tokenHead}")
    private String tokenHead;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        // 只拦截 /member/** 请求
        if (!path.startsWith("/member")) {
            chain.doFilter(request, response);
            return;
        }
        String authHeader = request.getHeader(tokenHeader);
        if (StrUtil.isNotEmpty(authHeader) && authHeader.startsWith(tokenHead)) {
            String token = authHeader.substring(tokenHead.length());
            Long userId = jwtTokenUtil.getUserIdFromToken(token);
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                String redisToken =
                        (String) redisService.get(MEMBER_TOKEN_KEY + userId);
                if (!token.equals(redisToken)) {
                    chain.doFilter(request, response);
                    return;
                }
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId, null, Collections.emptyList());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource()
                                .buildDetails(request));
                SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
