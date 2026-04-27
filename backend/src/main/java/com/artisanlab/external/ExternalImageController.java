package com.artisanlab.external;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external/images")
public class ExternalImageController {
    private final ExternalImageService externalImageService;
    private final AuthContext authContext;

    public ExternalImageController(ExternalImageService externalImageService, AuthContext authContext) {
        this.externalImageService = externalImageService;
        this.authContext = authContext;
    }

    @PostMapping
    public ApiResponse<ExternalImageDtos.SearchResponse> search(
            @Valid @RequestBody ExternalImageDtos.SearchRequest request
    ) {
        return ApiResponse.ok(externalImageService.search(authContext.requireUserId(), request));
    }

    @PostMapping("/import")
    public ApiResponse<ExternalImageDtos.ImportedImageResponse> importImage(
            @Valid @RequestBody ExternalImageDtos.ImportRequest request
    ) {
        return ApiResponse.ok(externalImageService.importImage(authContext.requireUserId(), request));
    }
}
