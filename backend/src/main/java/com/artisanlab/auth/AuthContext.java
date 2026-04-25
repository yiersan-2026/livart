package com.artisanlab.auth;

import com.artisanlab.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthContext {
    private static final ThreadLocal<AuthDtos.AuthUser> CURRENT_USER = new ThreadLocal<>();

    public void setCurrentUser(AuthDtos.AuthUser user) {
        CURRENT_USER.set(user);
    }

    public AuthDtos.AuthUser currentUser() {
        return CURRENT_USER.get();
    }

    public UUID requireUserId() {
        AuthDtos.AuthUser user = currentUser();
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public void clear() {
        CURRENT_USER.remove();
    }
}
