package com.artisanlab.userconfig;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/config")
public class UserApiConfigController {
    private final UserApiConfigService service;
    private final AuthContext authContext;

    public UserApiConfigController(UserApiConfigService service, AuthContext authContext) {
        this.service = service;
        this.authContext = authContext;
    }

    @GetMapping
    public ApiResponse<UserApiConfigDtos.Response> getConfig() {
        return ApiResponse.ok(service.getConfig(authContext.requireUserId()));
    }

    @PutMapping
    public ApiResponse<UserApiConfigDtos.Response> saveConfig(
            @Valid @RequestBody UserApiConfigDtos.SaveRequest request
    ) {
        return ApiResponse.ok(service.saveConfig(authContext.requireUserId(), request));
    }
}
