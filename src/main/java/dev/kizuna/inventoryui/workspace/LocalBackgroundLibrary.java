package dev.kizuna.inventoryui.workspace;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/** 本地 PNG/JPEG 入口；在解码前检查路径、压缩文件体积和像素上限。 */
public final class LocalBackgroundLibrary {
    public static final String PREFIX = "local:";
    public static final int MAX_ENTRIES = 64;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final int MAX_DIMENSION = 4096;
    public static final long MAX_PIXELS = 8_388_608L;
    private final Path directory;

    public LocalBackgroundLibrary(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public List<String> entries() throws IOException {
        // 显式刷新才扫描目录，稳定排序避免每次打开设置时跳换条目位置。
        Files.createDirectories(directory);
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> supported(path.getFileName().toString()))
                    .map(path -> PREFIX + path.getFileName())
                    .sorted()
                    .limit(MAX_ENTRIES)
                    .toList();
        }
    }

    private static boolean supported(String filename) {
        String normalized = filename.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".png")
                || normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg");
    }

    public BufferedImage decode(String id) throws IOException {
        // 文件名只允许当前目录一层，真实路径检查同时阻止目录穿越和符号链接逃逸。
        if (id == null || !id.startsWith(PREFIX)) {
            throw new IOException("invalid local background id");
        }
        Path path = directory.resolve(id.substring(PREFIX.length())).normalize();
        if (!directory.equals(path.getParent())
                || !supported(path.getFileName().toString())
                || !Files.isRegularFile(path)
                || !directory.toRealPath().equals(path.toRealPath().getParent())
                || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("background is outside its directory or exceeds the file limit");
        }
        try (var source = ImageIO.createImageInputStream(path.toFile())) {
            if (source == null) {
                throw new IOException("cannot open background image");
            }
            var readers = ImageIO.getImageReaders(source);
            if (!readers.hasNext()) {
                throw new IOException("unsupported background image");
            }
            var reader = readers.next();
            try {
                reader.setInput(source);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1
                        || height < 1
                        || width > MAX_DIMENSION
                        || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new IOException("background exceeds the pixel limit");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
