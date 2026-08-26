package com.winlator.core;

import android.content.Context;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public abstract class ZipUtils {
    public interface OnProgressListener {
        void onProgress(long done, long total);
    }
    private static void addFile(ZipArchiveOutputStream zip, File file, String entryName, AtomicLong doneRef, long totalSize, OnProgressListener listener) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(file, entryName);
        entry.setUnixMode(file.canExecute() ? 0100755 : 0100644);
        zip.putArchiveEntry(entry);

        try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
            int read;
            while ((read = inStream.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
                if (listener != null) listener.onProgress(doneRef.addAndGet(read), totalSize);
            }
        }
        zip.closeArchiveEntry();
    }

    private static void addLinkFile(ZipArchiveOutputStream zip, File file, String entryName) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
        entry.setUnixMode(0120777);
        zip.putArchiveEntry(entry);
        zip.write(FileUtils.readSymlink(file).getBytes());
        zip.closeArchiveEntry();
    }

    private static void addDirectory(ZipArchiveOutputStream zip, File folder, String entryName, AtomicLong doneRef, long totalSize, OnProgressListener listener) throws IOException {
        if (!entryName.isEmpty()) {
            ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
            entry.setUnixMode(folder.canExecute() ? 040755 : 040555);
            zip.putArchiveEntry(entry);
            zip.closeArchiveEntry();
        }

        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (FileUtils.isSymlink(file)) {
                addLinkFile(zip, file, entryName + file.getName());
            }
            else if (file.isDirectory()) {
                addDirectory(zip, file, entryName + file.getName() + "/", doneRef, totalSize, listener);
            }
            else addFile(zip, file, entryName + file.getName(), doneRef, totalSize, listener);
        }
    }

    public static void compress(File file, File destination) {
        compress(new File[]{file}, destination, -1, null);
    }

    public static void compress(File file, File destination, int level) {
        compress(new File[]{file}, destination, level, null);
    }

    public static void compress(File file, File destination, OnProgressListener listener) {
        compress(new File[]{file}, destination, -1, listener);
    }

    public static void compress(File file, File destination, int level, OnProgressListener listener) {
        compress(new File[]{file}, destination, level, listener);
    }

    public static void compress(File[] files, File destination) {
        compress(files, destination, -1, null);
    }

    public static void compress(File[] files, File destination, OnProgressListener listener) {
        compress(files, destination, -1, listener);
    }

    public static void compress(File[] files, File destination, int level, OnProgressListener listener) {
        try {
            long totalSize = 0;
            for (File file : files) {
                if (FileUtils.isSymlink(file)) continue;
                totalSize += getTotalSize(file);
            }

            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE))) {
                if (level >= 0) zip.setLevel(level);
                zip.setUseZip64(Zip64Mode.AsNeeded);
                boolean skipFirstEntry = files.length == 1 && files[0].getName().equals(".");
                AtomicLong doneRef = new AtomicLong();
                for (File file : files) {
                    if (FileUtils.isSymlink(file)) {
                        addLinkFile(zip, file, file.getName());
                    }
                    else if (file.isDirectory()) {
                        String entryName = skipFirstEntry ? "" : file.getName() + "/";
                        addDirectory(zip, file, entryName, doneRef, totalSize, listener);
                    }
                    else addFile(zip, file, file.getName(), doneRef, totalSize, listener);
                }
            }
        }
        catch (IOException e) {}
    }

    private static long getTotalSize(File file) {
        if (!file.isDirectory()) return file.length();
        File[] files = file.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File child : files) {
            if (FileUtils.isSymlink(child)) continue;
            size += getTotalSize(child);
        }
        return size;
    }

    public static boolean extract(File source, File destination) {
        return extract(source, destination, null);
    }

    public static boolean extract(File source, File destination, OnProgressListener listener) {
        try (ZipFile zipFile = new ZipFile(source)) {
            long totalSize = 0;
            Enumeration<ZipArchiveEntry> sizeEntries = zipFile.getEntries();
            while (sizeEntries.hasMoreElements()) {
                ZipArchiveEntry entry = sizeEntries.nextElement();
                if (!entry.isDirectory() && !entry.isUnixSymlink()) totalSize += entry.getSize();
            }

            long done = 0;
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                File file = new File(destination, entry.getName());

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isUnixSymlink()) {
                        String linkTarget;
                        try (InputStream inStream = zipFile.getInputStream(entry)) {
                            linkTarget = new String(StreamUtils.copyToByteArray(inStream), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            linkTarget = zipFile.getUnixSymlink(entry);
                        }
                        FileUtils.symlink(linkTarget, file.getAbsolutePath());
                        if (!FileUtils.isSymlink(file)) return false;
                    }
                    else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory()) parent.mkdirs();
                        try (InputStream inStream = zipFile.getInputStream(entry);
                            BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
                            int read;
                            while ((read = inStream.read(buffer)) != -1) {
                                outStream.write(buffer, 0, read);
                                done += read;
                                if (listener != null) listener.onProgress(done, totalSize);
                            }
                        }
                    }
                }

                if (!entry.isUnixSymlink()) FileUtils.chmod(file, 0771);
            }

            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static void extract(Context context, String assetFile, File destination) {
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open(assetFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File file = new File(destination, entry.getName());

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                        StreamUtils.copy(zip, outStream);
                        zip.closeEntry();
                    }
                }

                FileUtils.chmod(file, 0771);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static byte[] read(File source, String localPath) {
        try {
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

            ZipFile zipFile = new ZipFile(source);
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String entryName = entry.getName();
                boolean match = pathIsSuffix ? entryName.endsWith(localPath) : (pathIsPrefix ? entryName.startsWith(localPath) : entryName.equals(localPath));

                if (match && !entry.isDirectory() && !entry.isUnixSymlink()) {
                    try (InputStream inStream = zipFile.getInputStream(entry);
                        BufferedOutputStream outStream = new BufferedOutputStream(dataOutputStream, StreamUtils.BUFFER_SIZE)) {
                        if (!StreamUtils.copy(inStream, outStream)) return null;
                    }

                    return dataOutputStream.toByteArray();
                }
            }

            zipFile.close();
            return null;
        }
        catch (Exception e) {
            return null;
        }
    }
}
