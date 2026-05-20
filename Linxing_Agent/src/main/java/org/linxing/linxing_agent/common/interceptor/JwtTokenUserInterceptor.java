package org.linxing.linxing_agent.common.interceptor;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.linxing.linxing_agent.constant.JwtClaims;
import org.linxing.linxing_agent.common.userInfoMaintainer.BaseContext;
import org.linxing.linxing_agent.common.userInfoMaintainer.UserInfo;
import org.linxing.linxing_agent.common.config.JwtProperties;
import org.linxing.linxing_agent.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Slf4j
public class JwtTokenUserInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Long userId = Long.valueOf(claims.get(JwtClaims.USER_ID).toString());
            String username = claims.get(JwtClaims.USERNAME) != null
                    ? claims.get(JwtClaims.USERNAME).toString()
                    : null;
            
            log.info("用户认证成功: userId={}, username={}", userId, username);
            
            UserInfo userInfo = UserInfo.builder()
                    .userId(userId)
                    .username(username)
                    .build();
            BaseContext.setCurrentUser(userInfo);
            
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            log.error("JWT令牌校验失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        BaseContext.clear();
    }
}
