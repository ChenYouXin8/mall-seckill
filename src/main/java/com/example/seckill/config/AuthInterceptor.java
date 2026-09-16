package com.example.seckill.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * MVP 鉴权拦截器：校验 Authorization: Bearer &lt;seckill.test-token&gt;（文档 6.1）。
 * 生产环境替换为 JWT 解析；userId 取自 X-User-Id 请求头（默认 1001，演示用）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${seckill.test-token}")
    private String testToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            writeUnauthorized(response, "未认证");
            return false;
        }
        String token = auth.substring("Bearer ".length()).trim();
        if (!testToken.equals(token)) {
            writeUnauthorized(response, "未认证");
            return false;
        }

        // MVP：userId 从请求头解析（生产从 JWT 解析）
        String userIdHeader = request.getHeader("X-User-Id");
        long userId = 1001L;
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                userId = Long.parseLong(userIdHeader);
            } catch (NumberFormatException ignored) {
                // 非法 userId 使用默认值
            }
        }
        request.setAttribute("userId", userId);
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
