package com.colonysmp.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes files so a crash or a full disk can never leave half a file behind: the new content goes to a temporary
 * file next to the real one, is flushed to disk, and then replaces it in one rename.
 */
public final class SafeFiles {

    private SafeFiles() {}

    public static void write(File target, String content) throws IOException {
        Path dest = target.toPath();
        Path dir = dest.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        try (var out = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE)) {
            out.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            out.force(true);
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void save(YamlConfiguration y, File target) throws IOException {
        write(target, y.saveToString());
    }
}
