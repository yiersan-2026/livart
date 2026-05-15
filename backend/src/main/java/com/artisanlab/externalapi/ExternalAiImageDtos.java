package com.artisanlab.externalapi;

import com.artisanlab.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

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

    public record ImageToImageMultipartRequest(
            @NotBlank(message = "prompt 不能为空")
            @Size(max = 4000, message = "prompt 过长")
            String prompt,
            MultipartFile image,
            MultipartFile mask,
            List<MultipartFile> referenceImages,
            @Size(max = 36_000_000, message = "原图数据过大")
            String imageBase64,
            @Size(max = 36_000_000, message = "蒙版数据过大")
            String maskBase64,
            @Size(max = 4, message = "参考图最多 4 张")
            List<@Size(max = 36_000_000, message = "参考图数据过大") String> referenceImageBase64,
            @Size(max = 20, message = "画幅参数过长")
            String aspectRatio,
            @Size(max = 10, message = "清晰度参数过长")
            String imageResolution,
            Boolean enablePromptOptimization
    ) {
        public ImageToImageMultipartRequest {
            referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
            referenceImageBase64 = referenceImageBase64 == null ? List.of() : List.copyOf(referenceImageBase64);
        }

        public boolean promptOptimizationEnabled() {
            return Boolean.TRUE.equals(enablePromptOptimization);
        }

        public String imageBase64OrNull() {
            return normalizeText(imageBase64);
        }

        public String maskBase64OrNull() {
            return normalizeText(maskBase64);
        }

        public String imageDataUrl() {
            return multipartToDataUrl(image, "原图");
        }

        public String maskDataUrl() {
            return multipartToDataUrl(mask, "蒙版");
        }

        public List<String> referenceImageDataUrls() {
            List<String> fromFiles = referenceImages.stream()
                    .filter(file -> file != null && !file.isEmpty())
                    .map(file -> multipartToDataUrl(file, "参考图"))
                    .toList();
            if (!fromFiles.isEmpty()) {
                return fromFiles;
            }
            return referenceImageBase64.stream()
                    .map(ImageToImageMultipartRequest::normalizeText)
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }

        private static String multipartToDataUrl(MultipartFile file, String label) {
            if (file == null || file.isEmpty()) {
                return null;
            }
            try {
                String contentType = normalizeContentType(file.getContentType());
                return "data:%s;base64,%s".formatted(
                        contentType,
                        Base64.getEncoder().encodeToString(file.getBytes())
                );
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_READ_FAILED", "读取%s失败".formatted(label));
            }
        }

        private static String normalizeContentType(String contentType) {
            String normalized = normalizeText(contentType);
            if (normalized == null || normalized.isBlank()) {
                return "image/png";
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            return lower.startsWith("image/") ? lower : "image/png";
        }

        private static String normalizeText(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
