package com.zellijconnect.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SftpFileEntry implements Comparable<SftpFileEntry> {

    public final String name;
    public final String path;
    public final long size;
    public final long modifiedTime;
    public final boolean isDirectory;
    public final int permissions;

    public SftpFileEntry(String name, String path, long size, long modifiedTime,
                         boolean isDirectory, int permissions) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.modifiedTime = modifiedTime;
        this.isDirectory = isDirectory;
        this.permissions = permissions;
    }

    public boolean isHidden() {
        return name.startsWith(".");
    }

    public String getHumanSize() {
        if (isDirectory) return "";
        return humanReadableSize(size);
    }

    public static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
        return sdf.format(new Date(modifiedTime * 1000));
    }

    @Override
    public int compareTo(SftpFileEntry other) {
        // Folders first, then alphabetical
        if (this.isDirectory && !other.isDirectory) return -1;
        if (!this.isDirectory && other.isDirectory) return 1;
        return this.name.compareToIgnoreCase(other.name);
    }
}
