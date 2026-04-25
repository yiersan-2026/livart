package com.artisanlab.config;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.auth.AuthService;
import com.artisanlab.auth.JwtAuthenticationFilter;
import com.artisanlab.auth.JwtService;
import com.artisanlab.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            AuthService authService,
            AuthContext authContext,
            ObjectMapper objectMapper
    ) throws Exception {
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtService,
                authService,
                authContext,
                objectMapper
        );

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> writeError(
                                objectMapper,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "请先登录",
                                "AUTH_REQUIRED"
                        ))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeError(
                                objectMapper,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "没有权限访问该资源",
                                "ACCESS_DENIED"
                        )))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/index.css",
                                "/assets/**",
                                "/img/**",
                                "/favicon.ico",
                                "/api/health",
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/assets/*/content",
                                "/api/assets/*/preview",
                                "/api/assets/*/thumbnail",
                                "/ws/image-jobs"
                        ).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(
            ObjectMapper objectMapper,
            HttpServletResponse response,
            int status,
            String message,
            String code
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(message, code));
    }
}
