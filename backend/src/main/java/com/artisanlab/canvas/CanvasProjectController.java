package com.artisanlab.canvas;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/canvases")
public class CanvasProjectController {
    private final CanvasService canvasService;
    private final CanvasSaveQueueService canvasSaveQueueService;
    private final AuthContext authContext;

    public CanvasProjectController(CanvasService canvasService, CanvasSaveQueueService canvasSaveQueueService, AuthContext authContext) {
        this.canvasService = canvasService;
        this.canvasSaveQueueService = canvasSaveQueueService;
        this.authContext = authContext;
    }

    @GetMapping
    public ApiResponse<List<CanvasDtos.CanvasSummary>> listCanvases() {
        return ApiResponse.ok(canvasService.listCanvases(authContext.requireUserId()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CanvasDtos.CanvasResponse>> createCanvas(
            @RequestBody CanvasDtos.CreateCanvasRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(canvasService.createCanvas(authContext.requireUserId(), request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CanvasDtos.CanvasResponse> getCanvas(@PathVariable UUID id) {
        return ApiResponse.ok(canvasService.getCanvas(authContext.requireUserId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CanvasDtos.CanvasResponse>> saveCanvas(
            @PathVariable UUID id,
            @Valid @RequestBody CanvasDtos.SaveCanvasRequest request
    ) {
        CanvasDtos.CanvasResponse response = canvasSaveQueueService.enqueueCanvasSave(authContext.requireUserId(), id, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }
}
