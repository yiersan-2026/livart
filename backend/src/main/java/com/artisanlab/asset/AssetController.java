package com.artisanlab.asset;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    private final AssetService assetService;
    private final AuthContext authContext;

    public AssetController(AssetService assetService, AuthContext authContext) {
        this.assetService = assetService;
        this.authContext = authContext;
    }

    @PostMapping
    public ApiResponse<AssetDtos.AssetResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "canvasId", required = false) UUID canvasId
    ) {
        return ApiResponse.ok(assetService.upload(authContext.requireUserId(), canvasId, file));
    }

    @PostMapping("/{id}/rotate")
    public ApiResponse<AssetDtos.AssetResponse> rotate(
            @PathVariable UUID id,
            @RequestBody AssetDtos.AssetRotationRequest request
    ) {
        String direction = request == null ? "" : request.direction();
        Integer quarterTurns = request == null ? null : request.quarterTurns();
        return ApiResponse.ok(assetService.rotate(authContext.requireUserId(), id, direction, quarterTurns));
    }

    @GetMapping("/{id}/content")
    public void content(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        AssetService.AssetContent content = assetService.getContent(id);
        writeContent(content, response);
    }

    @GetMapping("/{id}/preview")
    public void preview(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        writeContent(assetService.getPreview(id), response);
    }

    @GetMapping("/{id}/thumbnail")
    public void thumbnail(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        writeContent(assetService.getThumbnail(id), response);
    }

    @GetMapping("/{id}/view/{width}")
    public void canvasView(
            @PathVariable UUID id,
            @PathVariable int width,
            HttpServletResponse response
    ) throws IOException {
        writeContent(assetService.getCanvasView(id, width), response);
    }

    private void writeContent(AssetService.AssetContent content, HttpServletResponse response) throws IOException {
        response.setContentType(content.contentType());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().getHeaderValue());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        try (InputStream inputStream = content.stream()) {
            StreamUtils.copy(inputStream, response.getOutputStream());
        }
    }
}
