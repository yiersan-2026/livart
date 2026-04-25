package com.artisanlab.auth;

import com.artisanlab.common.ApiException;
import com.artisanlab.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AuthService authService;
    private final AuthContext authContext;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            AuthService authService,
            AuthContext authContext,
            ObjectMapper objectMapper
    ) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.authContext = authContext;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = extractBearerToken(request.getHeader("Authorization"));
            if (!token.isBlank()) {
                authenticateToken(request, token);
            }
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            writeError(response, exception.status(), exception.getMessage(), exception.code());
        } finally {
            authContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticateToken(HttpServletRequest request, String token) {
        UUID userId = jwtService.verifyAndReadUserId(token);
        AuthDtos.AuthUser user = authService.findUserById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录状态已失效，请重新登录");
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        authContext.setCurrentUser(user);
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        String prefix = "Bearer ";
        return authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                ? authorization.substring(prefix.length()).trim()
                : "";
    }

    private void writeError(
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String code
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(message, code));
    }
}
