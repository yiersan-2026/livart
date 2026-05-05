package com.artisanlab.externalapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ExternalAiImageDtos {
    private ExternalAiImageDtos() {
    }

    public record TextToImageRequest(
            @NotBlank(message = "prompt 不能为空")
            @Size(max = 4000, message = "prompt 过长")
            String prompt,
            @Size(max = 20, message = "画幅参数过长")
            String aspectRatio,
            @Size(max = 10, message = "清晰度参数过长")
            String imageResolution,
            Boolean enablePromptOptimization
    ) {
        public boolean promptOptimizationEnabled() {
            return Boolean.TRUE.equals(enablePromptOptimization);
        }
    }

    public record ImageToImageRequest(
            @NotBlank(message = "prompt 不能为空")
            @Size(max = 4000, message = "prompt 过长")
            String prompt,
            @NotBlank(message = "原图不能为空")
            @Size(max = 36_000_000, message = "原图数据过大")
            String imageBase64,
            @Size(max = 36_000_000, message = "蒙版数据过大")
            String maskBase64,
            @Size(max = 4, message = "参考图最多 4 张")
            List<@NotBlank(message = "参考图不能为空") @Size(max = 36_000_000, message = "参考图数据过大") String> referenceImages,
            @Size(max = 20, message = "画幅参数过长")
            String aspectRatio,
            @Size(max = 10, message = "清晰度参数过长")
            String imageResolution,
            Boolean enablePromptOptimization
    ) {
        public ImageToImageRequest {
            referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
        }

        public boolean promptOptimizationEnabled() {
            return Boolean.TRUE.equals(enablePromptOptimization);
        }
    }
}
