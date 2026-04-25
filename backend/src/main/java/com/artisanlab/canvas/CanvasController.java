package com.artisanlab.canvas;

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

    public CanvasController(CanvasService canvasService, CanvasSaveQueueService canvasSaveQueueService) {
        this.canvasService = canvasService;
        this.canvasSaveQueueService = canvasSaveQueueService;
    }

    @GetMapping("/current")
    public ApiResponse<CanvasDtos.CanvasResponse> getCurrentCanvas() {
        return ApiResponse.ok(canvasService.getCurrentCanvas());
    }

    @PutMapping("/current")
    public ResponseEntity<ApiResponse<CanvasDtos.CanvasResponse>> saveCurrentCanvas(
            @Valid @RequestBody CanvasDtos.SaveCanvasRequest request
    ) {
        CanvasDtos.CanvasResponse response = canvasSaveQueueService.enqueueCurrentCanvasSave(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }
}
