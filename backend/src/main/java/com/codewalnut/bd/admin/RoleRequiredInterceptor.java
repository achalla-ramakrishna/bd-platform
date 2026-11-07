package com.codewalnut.bd.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A signed-in user with no role sees nothing but {@code /api/v1/me} until an Admin assigns one
 * (ADR-0004).
 */
@Configuration
public class RoleRequiredInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final CurrentUserProvider currentUser;

    public RoleRequiredInterceptor(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/v1/me", "/api/v1/public/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!currentUser.get().hasAnyRole()) {
            throw new AccessDeniedException("No role assigned yet. Ask an Admin to assign one.");
        }
        return true;
    }
}
