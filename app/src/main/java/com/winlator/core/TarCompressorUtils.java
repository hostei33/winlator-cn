package com.winlator.core;

import android.content.Context;
import android.net.Uri;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD}

    public interface OnExtractFileListener {
        File onExtractFile(File destination, long size);
    }

    public interface OnProgressListener {
        void onProgress(long done, long total);
    }

    /**
     * 压缩过滤:返回 true 表示跳过该文件(不写入 tar)。
     * 若跳过的是目录,则整棵子树都不打包(不递归、不计入总大小)。
     */
    public interface FileFilter {
        boolean shouldSkip(File file, String entryPath);
    }

    // zstd skippable frame magic 值范围 0x184D2A50-0x184D2A5F,此处使用最小值
    private static final int SKIPPABLE_FRAME_MAGIC = 0x184D2A50;

    /**
     * 向输出流写入一个 zstd skippable frame。
     * skippable frame 是 zstd 标准允许的自定义数据帧,解码器会自动跳过,
     * 因此可在备份文件头部附加元数据(如容器名)而不影响解压。
     */
    public static void writeSkippableFrame(OutputStream out, byte[] data) throws IOException {
        // zstd 规范:magic(4B) 与 frame size(4B) 均为 little-endian
        out.write(SKIPPABLE_FRAME_MAGIC);
        out.write(SKIPPABLE_FRAME_MAGIC >> 8);
        out.write(SKIPPABLE_FRAME_MAGIC >> 16);
        out.write(SKIPPABLE_FRAME_MAGIC >> 24);
        int len = data.length;
        out.write(len);
        out.write(len >> 8);
        out.write(len >> 16);
        out.write(len >> 24);
        out.write(data);
    }

    /**
     * 读取备份文件头部的 skippable frame 元数据(JSON 字节)。
     * zstd 规范:magic(4B,0x184D2A50-0x184D2A5F)与 frame size(4B)均为 little-endian。
     * 文件不是以 skippable frame 开头(如无元数据的旧版备份)时返回 null。
     */
    public static byte[] readMeta(File source) {
        if (source == null || !source.isFile()) return null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE)) {
            int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
            if (b0 < 0) return null;
            int magic = (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
            if (magic < SKIPPABLE_FRAME_MAGIC || magic > 0x184D2A5F) return null;
            int s0 = in.read(), s1 = in.read(), s2 = in.read(), s3 = in.read();
            if (s0 < 0) return null;
            int len = (s0 & 0xFF) | ((s1 & 0xFF) << 8) | ((s2 & 0xFF) << 16) | ((s3 & 0xFF) << 24);
            // 防御:拒绝空帧或超大帧(元数据仅描述信息,1MB 足够)
            if (len <= 0 || len > 1024 * 1024) return null;
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int read = in.read(data, off, len - off);
                if (read < 0) return null;
                off += read;
            }
            return data;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static void addFile(ArchiveOutputStream tar, File file, String entryName, AtomicLong doneRef, long totalSize, OnProgressListener listener, FileFilter filter) {
        if (filter != null && filter.shouldSkip(file, entryName)) return;
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                int read;
                while ((read = inStream.read(buffer)) != -1) {
                    tar.write(buffer, 0, read);
                    if (listener != null) listener.onProgress(doneRef.addAndGet(read), totalSize);
                }
            }
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName, FileFilter filter) {
        if (filter != null && filter.shouldSkip(file, entryName)) return;
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath, AtomicLong doneRef, long totalSize, OnProgressListener listener, FileFilter filter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            String entryPath = basePath+file.getName();
            if (FileUtils.isSymlink(file)) {
                addLinkFile(tar, file, entryPath, filter);
            }
            else if (file.isDirectory()) {
                if (filter != null && filter.shouldSkip(file, entryPath+"/")) continue;
                String entryName = entryPath + "/";
                tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                tar.closeArchiveEntry();
                addDirectory(tar, file, entryName, doneRef, totalSize, listener, filter);
            }
            else addFile(tar, file, entryPath, doneRef, totalSize, listener, filter);
        }
    }

    public static void compress(Type type, File file, File destination) {
        compress(type, file, destination, 3);
    }

    public static void compress(Type type, File file, File destination, int level) {
        compress(type, file, destination, level, null);
    }

    public static void compress(Type type, File file, File destination, int level, OnProgressListener listener) {
        compress(type, file, destination, level, listener, null);
    }

    public static void compress(Type type, File file, File destination, int level, OnProgressListener listener, byte[] meta) {
        compress(type, new File[]{file}, destination, level, listener, meta);
    }

    public static void compress(Type type, File file, File destination, int level, OnProgressListener listener, byte[] meta, FileFilter filter) {
        compress(type, new File[]{file}, destination, level, listener, meta, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level) {
        compress(type, files, destination, level, null, null);
    }

    public static void compress(Type type, File[] files, File destination, int level, OnProgressListener listener) {
        compress(type, files, destination, level, listener, null);
    }

    /**
     * 压缩文件/目录到 tar.zst,可在文件头部附加一个 skippable frame 元数据(JSON)。
     * skippable frame 不参与解压,解码器自动跳过,用于携带备份描述信息。
     */
    public static void compress(Type type, File[] files, File destination, int level, OnProgressListener listener, byte[] meta) {
        compress(type, files, destination, level, listener, meta, null);
    }

    public static void compress(Type type, File[] files, File destination, int level, OnProgressListener listener, byte[] meta, FileFilter filter) {
        try {
            OutputStream destStream = new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
            try {
                if (meta != null && meta.length > 0) writeSkippableFrame(destStream, meta);
                compressTo(type, destStream, files, level, listener, filter);
            }
            finally {
                destStream.close();
            }
        }
        catch (IOException e) {}
    }

    private static void compressTo(Type type, OutputStream destStream, File[] files, int level, OnProgressListener listener, FileFilter filter) throws IOException {
        try (OutputStream outStream = getCompressorOutputStream(type, destStream, level);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            long totalSize = 0;
            for (File file : files) {
                if (!FileUtils.isSymlink(file)) totalSize += getTotalSize(file, file.getName()+"/", filter);
            }
            boolean skipFirstEntry = files.length == 1 && files[0].getName().equals(".");
            AtomicLong doneRef = new AtomicLong();
            for (File file : files) {
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName(), filter);
                }
                else if (file.isDirectory()) {
                    String basePath = "";
                    if (!skipFirstEntry) {
                        basePath = file.getName() + "/";
                        if (filter == null || !filter.shouldSkip(file, basePath)) {
                            tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                            tar.closeArchiveEntry();
                        }
                        else continue;
                    }
                    addDirectory(tar, file, basePath, doneRef, totalSize, listener, filter);
                }
                else addFile(tar, file, file.getName(), doneRef, totalSize, listener, filter);
            }
            tar.finish();
        }
    }

    private static long getTotalSize(File file, String entryPath, FileFilter filter) {
        if (!file.isDirectory()) {
            if (filter != null && filter.shouldSkip(file, entryPath)) return 0;
            return file.length();
        }
        File[] files = file.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File child : files) {
            if (FileUtils.isSymlink(child)) continue;
            String childEntryPath = entryPath + child.getName();
            if (child.isDirectory()) {
                childEntryPath += "/";
                if (filter != null && filter.shouldSkip(child, childEntryPath)) continue;
                size += getTotalSize(child, childEntryPath, filter);
            }
            else {
                if (filter != null && filter.shouldSkip(child, childEntryPath)) continue;
                size += getTotalSize(child, childEntryPath, filter);
            }
        }
        return size;
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try {
            return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try (InputStream inStream = getCompressorInputStream(type, source);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                String entryName = entry.getName();
                // 路径穿越防护:拒绝绝对路径与含 ".." 路径段的条目
                String normalized = new File(destination, entryName).getPath();
                if (entryName.startsWith("/") || entryName.contains("/../") || entryName.endsWith("/..")
                        || entryName.equals("..") || entryName.startsWith("../")
                        || !normalized.startsWith(destination.getPath())) {
                    return false;
                }
                File file = new File(destination, entryName);

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    }
                    else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    public static long getContentLength(Type type, Context context, String assetFile, File destination) {
        AtomicLong totalSizeRef = new AtomicLong();
        extract(type, context, assetFile, destination, (file, size) -> {
            totalSizeRef.addAndGet(size);
            return null;
        });
        return totalSizeRef.get();
    }

    public static long getContentLength(Type type, File source, File destination) {
        AtomicLong totalSizeRef = new AtomicLong();
        extract(type, source, destination, (file, size) -> {
            totalSizeRef.addAndGet(size);
            return null;
        });
        return totalSizeRef.get();
    }

    public static byte[] read(Type type, File source, String localPath) {
        boolean pathIsPrefix = false;
        boolean pathIsSuffix = false;

        if (localPath.startsWith("*")) {
            pathIsSuffix = true;
        }
        else if (localPath.endsWith("*")) {
            pathIsPrefix = true;
        }

        localPath = localPath.replace("*", "");
        ByteArrayOutputStream dataOutputStream = new ByteArrayOutputStream();

        try (InputStream inStream = getCompressorInputStream(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE));
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                String entryName = entry.getName();
                boolean match = pathIsSuffix ? entryName.endsWith(localPath) : (pathIsPrefix ? entryName.startsWith(localPath) : entryName.equals(localPath));

                if (match && !entry.isDirectory() && !entry.isSymbolicLink()) {
                    try (BufferedOutputStream outStream = new BufferedOutputStream(dataOutputStream, StreamUtils.BUFFER_SIZE)) {
                        if (!StreamUtils.copy(tar, outStream)) return null;
                    }
                    return dataOutputStream.toByteArray();
                }
            }
            return null;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return null;
    }

    private static OutputStream getCompressorOutputStream(Type type, OutputStream destStream, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(destStream, level);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(destStream, level);
        }
        return null;
    }
}
