package com.artisanlab.delivery;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private final ExportService exportService;
    private final AuthContext authContext;

    public ExportController(ExportService exportService, AuthContext authContext) {
        this.exportService = exportService;
        this.authContext = authContext;
    }

    @PostMapping("/images")
    public ApiResponse<ExportDtos.ExportResponse> createImageExport(
            @Valid @RequestBody ExportDtos.ImageExportRequest request
    ) {
        return ApiResponse.ok(exportService.createImageExport(authContext.requireUserId(), request));
    }

    @GetMapping("/{exportId}/download")
    public void download(@PathVariable UUID exportId, HttpServletResponse response) throws IOException {
        ExportService.ExportFile exportFile = exportService.getExportFile(authContext.requireUserId(), exportId);
        response.setContentType("application/zip");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                        .filename(exportFile.filename(), java.nio.charset.StandardCharsets.UTF_8)
                        .build()
                        .toString()
        );
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLengthLong(Files.size(exportFile.path()));

        try (InputStream inputStream = Files.newInputStream(exportFile.path())) {
            StreamUtils.copy(inputStream, response.getOutputStream());
        }
    }
}
