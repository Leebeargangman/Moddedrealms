package net.moddedrealms.smp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Saves a YAML file so a crash, a kill or a full disk can't leave it half written: the new text goes to
 * name.tmp, is forced to disk, then replaces the old file in one rename. Used for every data file of the plugin.
 */
public final class SafeYaml {
    private SafeYaml() {
    }

    public static void save(FileConfiguration y, File target) throws IOException {
        byte[] bytes = y.saveToString().getBytes(StandardCharsets.UTF_8);
        Path dest = target.toPath();
        Path dir = dest.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) out.write(buf);
            out.force(true);
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
