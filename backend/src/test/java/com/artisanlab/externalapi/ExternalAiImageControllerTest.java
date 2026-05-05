package com.artisanlab.externalapi;

import com.artisanlab.ai.AiProxyService;
import com.artisanlab.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExternalAiImageControllerTest {
    private final AiProxyService aiProxyService = mock(AiProxyService.class);
    private final ExternalApiKeyAuthService authService = mock(ExternalApiKeyAuthService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new ExternalAiImageController(aiProxyService, authService)
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsTextToImageJobForExternalCaller() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authService.requireAuthorizedOwner(any())).thenReturn(ownerId);
        when(aiProxyService.createExternalTextToImageJob(any(), any()))
                .thenReturn(Map.of("jobId", "job-1", "status", "queued"));

        mockMvc.perform(post("/api/external/v1/images/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Livart-Api-Key", "test-key")
                        .content(objectMapper.writeValueAsBytes(new ExternalAiImageDtos.TextToImageRequest(
                                "小猫抓蝴蝶",
                                "1:1",
                                "2k",
                                false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value("job-1"))
                .andExpect(jsonPath("$.data.status").value("queued"));

        verify(aiProxyService).createExternalTextToImageJob(any(), any());
    }

    @Test
    void createsImageEditJobForExternalCaller() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authService.requireAuthorizedOwner(any())).thenReturn(ownerId);
        when(aiProxyService.createExternalImageEditJob(any(), any()))
                .thenReturn(Map.of("jobId", "job-2", "status", "queued"));

        mockMvc.perform(post("/api/external/v1/images/edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Livart-Api-Key", "test-key")
                        .content(objectMapper.writeValueAsBytes(new ExternalAiImageDtos.ImageToImageRequest(
                                "把鞋子换成红色",
                                "ZmFrZQ==",
                                null,
                                java.util.List.of(),
                                "9:16",
                                "2k",
                                true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value("job-2"));

        verify(aiProxyService).createExternalImageEditJob(any(), any());
    }

    @Test
    void queriesExternalImageJobByJobId() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(authService.requireAuthorizedOwner(any())).thenReturn(ownerId);
        when(aiProxyService.getExternalImageJobSnapshot(any(), anyString()))
                .thenReturn(Map.of("jobId", "job-3", "status", "completed"));

        mockMvc.perform(get("/api/external/v1/images/jobs/job-3")
                        .header("X-Livart-Api-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").value("job-3"))
                .andExpect(jsonPath("$.data.status").value("completed"));

        verify(aiProxyService).getExternalImageJobSnapshot(ownerId, "job-3");
    }
}
