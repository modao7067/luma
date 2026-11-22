package io.github.modao7067.luma.shared;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class FontCacheInvalidator {
    private static final int MAX_MARKER_BYTES = 1024;
    private static final String[] SHADER_FILES = {
        "com.android.skia.shaders_cache", "com.android.opengl.shaders_cache",
        "com.android.skia.shaders_cache.tmp", "com.android.opengl.shaders_cache.tmp"
    };

    static String fingerprint(ZipFile apk) throws IOException {
        if (apk.getEntry("assets/terminal-fonts-manifest.json") == null)
            throw new IOException("Missing terminal font manifest");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("luma-graphics-apk-cache-2\n".getBytes(StandardCharsets.US_ASCII));
            ArrayList<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> source = apk.entries();
            while (source.hasMoreElements()) {
                ZipEntry entry = source.nextElement();
                if (!entry.isDirectory()) entries.add(entry);
            }
            entries.sort(Comparator.comparing(ZipEntry::getName));
            ByteBuffer metadata = ByteBuffer.allocate(24);
            for (ZipEntry entry : entries) {
                if (entry.getCrc() < 0 || entry.getSize() < 0)
                    throw new IOException("Incomplete APK entry metadata");
                byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
                metadata.clear();
                metadata.putLong(name.length).putLong(entry.getSize()).putLong(entry.getCrc());
                digest.update(metadata.array());
                digest.update(name);
            }
            byte[] hash = digest.digest();
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) result.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean markerMatches(Path markerPath, String fingerprint) throws IOException {
        if (!Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.size(markerPath) > MAX_MARKER_BYTES) return false;
        byte[] buffer = new byte[MAX_MARKER_BYTES + 1];
        int total = 0;
        try (InputStream in = Files.newInputStream(markerPath)) {
            int n;
            while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) != -1) {
                total += n;
            }
        }
        if (total > MAX_MARKER_BYTES) return false;
        return fingerprint.equals(new String(buffer, 0, total, StandardCharsets.US_ASCII));
    }

    static synchronized boolean refresh(File marker, String fingerprint, File... cacheDirs) throws IOException {
        Path markerPath = marker.toPath();
        Path parent = markerPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        String lockName = markerPath.getFileName().toString() + ".lock";
        Path lockPath = parent != null ? parent.resolve(lockName) : markerPath.resolveSibling(lockName);
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lockChannel.lock()) {
            if (markerMatches(markerPath, fingerprint)) return false;

            for (File directory : cacheDirs) {
                if (directory == null) throw new IOException("Graphics cache directory unavailable");
                for (String name : SHADER_FILES) {
                    Path file = new File(directory, name).toPath();
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Expected a graphics cache file: " + file);
                    Files.deleteIfExists(file);
                }
            }

            Path tempDir = parent != null ? parent : new File(".").toPath();
            Path temporary = Files.createTempFile(tempDir, "font-cache-", ".tmp");
            try {
                Files.write(temporary, fingerprint.getBytes(StandardCharsets.US_ASCII));
                try {
                    Files.move(temporary, markerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        }
    }
}
