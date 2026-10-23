package com.academy.paybridge.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 401 and 403 answers in the same JSON shape as every other error: {"error": "...", "message": "..."}. */
@Component
public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                "Authentication is required: send a valid Bearer access token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                "You do not have permission to do this");
    }

    private static void write(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
