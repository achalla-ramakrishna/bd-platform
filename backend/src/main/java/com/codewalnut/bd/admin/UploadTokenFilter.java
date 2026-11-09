package com.codewalnut.bd.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lets a script upload account research without a browser sign-in (ADR-0010): a POST to the research
 * upload endpoint with {@code Authorization: Bearer <token>} acts as the configured user, whose roles
 * still apply. Only that one endpoint accepts the token, and it is off unless a token of at least 32
 * characters is set in RESEARCH_UPLOAD_TOKEN.
 */
class UploadTokenFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/research/uploads";
    static final int MIN_LENGTH = 32;

    /** Requests this filter handles: the upload endpoint with a bearer token. CSRF does not apply to them. */
    static final RequestMatcher BEARER_UPLOAD = request -> "POST".equals(request.getMethod())
        && PATH.equals(request.getRequestURI())
        && request.getHeader("Authorization") != null
        && request.getHeader("Authorization").startsWith("Bearer ");

    private final byte[] token;
    private final String actAs;

    UploadTokenFilter(String token, String actAs) {
        boolean on = token != null && token.strip().length() >= MIN_LENGTH && actAs != null && !actAs.isBlank();
        this.token = on ? token.strip().getBytes(StandardCharsets.UTF_8) : null;
        this.actAs = on ? actAs.strip().toLowerCase() : null;
    }

    boolean enabled() {
        return token != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled() && BEARER_UPLOAD.matches(request)) {
            byte[] given = request.getHeader("Authorization").substring("Bearer ".length()).strip()
                .getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(token, given)) {
                var auth = UsernamePasswordAuthenticationToken.authenticated(actAs, null, List.of());
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
