package com.artisanlab.canvas;

import com.artisanlab.auth.AuthContext;
import com.artisanlab.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/canvas")
public class CanvasController {
    private final CanvasService canvasService;
    private final CanvasSaveQueueService canvasSaveQueueService;
    private final AuthContext authContext;

    public CanvasController(CanvasService canvasService, CanvasSaveQueueService canvasSaveQueueService, AuthContext authContext) {
        this.canvasService = canvasService;
        this.canvasSaveQueueService = canvasSaveQueueService;
        this.authContext = authContext;
    }

    @GetMapping("/current")
    public ApiResponse<CanvasDtos.CanvasResponse> getCurrentCanvas() {
        return ApiResponse.ok(canvasService.getCurrentCanvas(authContext.requireUserId()));
    }

    @PutMapping("/current")
    public ResponseEntity<ApiResponse<CanvasDtos.CanvasResponse>> saveCurrentCanvas(
            @Valid @RequestBody CanvasDtos.SaveCanvasRequest request
    ) {
        CanvasDtos.CanvasResponse response = canvasSaveQueueService.enqueueCurrentCanvasSave(authContext.requireUserId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }
}
