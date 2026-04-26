package com.artisanlab.asset;

import com.artisanlab.config.ArtisanProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssetServiceModelInputTest {
    private final AssetService assetService = new AssetService(null, null, new ArtisanProperties(
            new ArtisanProperties.Auth("test-secret-at-least-long-enough", 7),
            new ArtisanProperties.Canvas(UUID.randomUUID()),
            new ArtisanProperties.Cors("*"),
            new ArtisanProperties.Minio("http://localhost:9000", "minio", "minio123", "bucket"),
            new ArtisanProperties.Rabbitmq("canvas-save", "canvas-save-dlq")
    ));

    @Test
    void compressesLargeOpaqueImagesToJpegForModelInput() throws Exception {
        byte[] originalBytes = createImageBytes(3200, 1200, BufferedImage.TYPE_INT_RGB, "png");

        AssetService.PreparedImageContent prepared = assetService.prepareModelInputImage(originalBytes, "image/png");

        BufferedImage preparedImage = ImageIO.read(new ByteArrayInputStream(prepared.bytes()));
        assertThat(prepared.contentType()).isEqualTo("image/jpeg");
        assertThat(Math.max(preparedImage.getWidth(), preparedImage.getHeight())).isEqualTo(2048);
    }

    @Test
    void keepsAlphaImagesAsPngForModelInput() throws Exception {
        byte[] originalBytes = createImageBytes(3200, 1200, BufferedImage.TYPE_INT_ARGB, "png");

        AssetService.PreparedImageContent prepared = assetService.prepareModelInputImage(originalBytes, "image/png");

        BufferedImage preparedImage = ImageIO.read(new ByteArrayInputStream(prepared.bytes()));
        assertThat(prepared.contentType()).isEqualTo("image/png");
        assertThat(preparedImage.getColorModel().hasAlpha()).isTrue();
        assertThat(Math.max(preparedImage.getWidth(), preparedImage.getHeight())).isEqualTo(2048);
    }

    @Test
    void convertsWebpInputsToJpegOrPngForUpstreamCompatibility() throws Exception {
        byte[] originalBytes = createImageBytes(800, 600, BufferedImage.TYPE_INT_RGB, "png");

        AssetService.PreparedImageContent prepared = assetService.prepareModelInputImage(originalBytes, "image/webp");

        assertThat(prepared.contentType()).isEqualTo("image/jpeg");
    }

    private byte[] createImageBytes(int width, int height, int imageType, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, imageType);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(220, 80, 80, image.getColorModel().hasAlpha() ? 180 : 255));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, format, outputStream);
        return outputStream.toByteArray();
    }
}
