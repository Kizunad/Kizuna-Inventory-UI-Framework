package dev.kizuna.inventoryui.workspace;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

class LocalBackgroundLibraryTest {
    @TempDir Path directory;

    @Test
    void loadsImagesButRejectsEscapesCorruptionAndOversizedDimensions() throws Exception {
        // 本地图片是用户可控输入，路径和分配预算在接触 GPU 前验证。
        Path backgrounds = directory.resolve("backgrounds");
        var library = new LocalBackgroundLibrary(backgrounds);
        assertTrue(library.entries().isEmpty());
        ImageIO.write(
                new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB),
                "png",
                backgrounds.resolve("valid.png").toFile());
        assertEquals(8, library.decode("local:valid.png").getWidth());
        Files.writeString(backgrounds.resolve("broken.png"), "invalid");
        assertThrows(IOException.class, () -> library.decode("local:broken.png"));
        assertThrows(IOException.class, () -> library.decode("local:../valid.png"));
        Files.copy(backgrounds.resolve("valid.png"), directory.resolve("outside.png"));
        Files.createSymbolicLink(
                backgrounds.resolve("escape.png"), directory.resolve("outside.png"));
        assertThrows(IOException.class, () -> library.decode("local:escape.png"));
        ImageIO.write(
                new BufferedImage(
                        LocalBackgroundLibrary.MAX_DIMENSION + 1, 1, BufferedImage.TYPE_INT_RGB),
                "png",
                backgrounds.resolve("oversized.png").toFile());
        assertThrows(IOException.class, () -> library.decode("local:oversized.png"));
    }
}
