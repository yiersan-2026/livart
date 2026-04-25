package com.artisanlab.auth;

import com.artisanlab.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_@.\\-]{3,80}$");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String displayName = normalizeDisplayName(request.displayName(), username);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        try {
            userMapper.insertUser(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }

        return createJwtResponse(userMapper.findById(user.getId()));
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String username = normalizeUsername(request.username());
        UserEntity user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }

        return createJwtResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthUser findUserById(UUID userId) {
        UserEntity user = userMapper.findById(userId);
        return user == null ? null : toUser(user);
    }

    public void logout() {
    }

    private AuthDtos.AuthResponse createJwtResponse(UserEntity user) {
        return jwtService.issueToken(toUser(user));
    }

    private AuthDtos.AuthUser toUser(UserEntity user) {
        return new AuthDtos.AuthUser(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getCreatedAt()
        );
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名只能包含字母、数字、下划线、横线、点或邮箱符号，长度 3-80");
        }
        return normalized;
    }

    private String normalizeDisplayName(String displayName, String username) {
        if (displayName == null || displayName.isBlank()) {
            return username;
        }
        String trimmed = displayName.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

}
