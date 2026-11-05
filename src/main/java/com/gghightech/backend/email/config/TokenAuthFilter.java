package com.gghightech.backend.email.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two callers, two tokens, never interchangeable:
 * <ul>
 * <li>{@code /api/v1/email/**}: the FastAPI proxy, which has already checked
 * the user is a SUPER_ADMIN. Sends {@code X-Service-Token}, plus the admin's
 * email in {@code X-Actor-Email} for the audit trail.</li>
 * <li>{@code /internal/**}: James (delivery events, DKIM key fetches). Sends
 * {@code Authorization: Bearer <internal token>}.</li>
 * </ul>
 * A blank configured token rejects every request to that area.
 */
@Component
public class TokenAuthFilter extends OncePerRequestFilter {
    public static final String ACTOR_ATTRIBUTE = "email.actor";

    private final EmailProperties properties;

    public TokenAuthFilter(EmailProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/email")) {
            if (!matches(properties.serviceToken(), request.getHeader("X-Service-Token"))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String actor = request.getHeader("X-Actor-Email");
            request.setAttribute(ACTOR_ATTRIBUTE, actor == null || actor.isBlank() ? "unknown" : actor.strip());
        } else if (path.startsWith("/internal/")) {
            String header = request.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            if (!matches(properties.internalToken(), token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
