package com.zellijconnect.app;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects file types from extensions for rendering decisions.
 */
public final class FileTypeDetector {

    public enum FileType {
        MARKDOWN,    // Render with Markwon full formatting
        SOURCE_CODE, // Render with syntax highlighting
        PLAIN_TEXT,  // Monospace plain text
        IMAGE,       // Phase 3
        BINARY       // Show "binary file" message
    }

    // Extension → Prism4j language name (for syntax highlighting)
    private static final Map<String, String> LANGUAGE_MAP = new HashMap<>();
    static {
        LANGUAGE_MAP.put("java", "java");
        LANGUAGE_MAP.put("kt", "kotlin");
        LANGUAGE_MAP.put("kts", "kotlin");
        LANGUAGE_MAP.put("py", "python");
        LANGUAGE_MAP.put("js", "javascript");
        LANGUAGE_MAP.put("mjs", "javascript");
        LANGUAGE_MAP.put("jsx", "javascript");
        LANGUAGE_MAP.put("ts", "javascript");   // Prism4j lacks TypeScript; JS is close enough
        LANGUAGE_MAP.put("tsx", "javascript");
        LANGUAGE_MAP.put("json", "json");
        LANGUAGE_MAP.put("yml", "yaml");
        LANGUAGE_MAP.put("yaml", "yaml");
        LANGUAGE_MAP.put("xml", "markup");
        LANGUAGE_MAP.put("html", "markup");
        LANGUAGE_MAP.put("htm", "markup");
        LANGUAGE_MAP.put("svg", "markup");
        LANGUAGE_MAP.put("css", "css");
        // bash/shell not available in Prism4j — render as plain code
        LANGUAGE_MAP.put("sh", "clike");
        LANGUAGE_MAP.put("bash", "clike");
        LANGUAGE_MAP.put("zsh", "clike");
        LANGUAGE_MAP.put("c", "c");
        LANGUAGE_MAP.put("h", "c");
        LANGUAGE_MAP.put("cpp", "cpp");
        LANGUAGE_MAP.put("hpp", "cpp");
        LANGUAGE_MAP.put("cc", "cpp");
        LANGUAGE_MAP.put("go", "go");
        LANGUAGE_MAP.put("rs", "clike");  // rust not available in Prism4j
        LANGUAGE_MAP.put("sql", "sql");
        LANGUAGE_MAP.put("groovy", "groovy");
        LANGUAGE_MAP.put("gradle", "groovy");
        LANGUAGE_MAP.put("scala", "scala");
        LANGUAGE_MAP.put("swift", "swift");
        LANGUAGE_MAP.put("dart", "dart");
        LANGUAGE_MAP.put("cs", "csharp");
        LANGUAGE_MAP.put("rb", "clike");  // ruby not available in Prism4j
        LANGUAGE_MAP.put("toml", "yaml"); // toml not available in Prism4j
        LANGUAGE_MAP.put("makefile", "makefile");
    }

    // Image extensions (Phase 3)
    private static final String[] IMAGE_EXTENSIONS = {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico"
    };

    // Known binary extensions
    private static final String[] BINARY_EXTENSIONS = {
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "jar", "war", "ear", "class", "dex", "apk",
        "so", "dylib", "dll", "exe", "o", "a",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "mp3", "mp4", "avi", "mkv", "mov", "wav", "flac",
        "ttf", "otf", "woff", "woff2", "eot",
        "sqlite", "db"
    };

    // Plain text extensions (no syntax highlighting)
    private static final String[] TEXT_EXTENSIONS = {
        "txt", "log", "csv", "tsv", "cfg", "conf", "ini",
        "env", "properties", "gitignore", "gitattributes",
        "dockerignore", "editorconfig", "LICENSE", "AUTHORS"
    };

    private FileTypeDetector() {}

    public static FileType detect(String fileName) {
        String ext = getExtension(fileName).toLowerCase();

        // Markdown
        if ("md".equals(ext) || "markdown".equals(ext) || "mdx".equals(ext)) {
            return FileType.MARKDOWN;
        }

        // Source code with syntax highlighting
        if (LANGUAGE_MAP.containsKey(ext)) {
            return FileType.SOURCE_CODE;
        }

        // Images
        for (String ie : IMAGE_EXTENSIONS) {
            if (ie.equals(ext)) return FileType.IMAGE;
        }

        // Known binary
        for (String be : BINARY_EXTENSIONS) {
            if (be.equals(ext)) return FileType.BINARY;
        }

        // Known plain text
        for (String te : TEXT_EXTENSIONS) {
            if (te.equals(ext)) return FileType.PLAIN_TEXT;
        }

        // Special filenames without extensions
        String name = fileName.toLowerCase();
        if ("makefile".equals(name) || "dockerfile".equals(name) ||
            "vagrantfile".equals(name) || "rakefile".equals(name) ||
            "gemfile".equals(name) || "procfile".equals(name)) {
            return FileType.SOURCE_CODE;
        }

        // Default: treat as plain text (will check binary heuristic at content level)
        return FileType.PLAIN_TEXT;
    }

    /**
     * Get the Prism4j language name for syntax highlighting.
     * Returns null if no language mapping exists.
     */
    public static String getLanguage(String fileName) {
        String ext = getExtension(fileName).toLowerCase();
        String lang = LANGUAGE_MAP.get(ext);
        if (lang != null) return lang;

        // Special filenames
        String name = fileName.toLowerCase();
        if ("makefile".equals(name)) return "makefile";
        if ("dockerfile".equals(name)) return "clike";
        return null;
    }

    /**
     * Heuristic check for binary content: if any null bytes in first 8KB, it's binary.
     */
    public static boolean isBinaryContent(byte[] content) {
        int checkLength = Math.min(content.length, 8192);
        for (int i = 0; i < checkLength; i++) {
            if (content[i] == 0) return true;
        }
        return false;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot + 1);
        }
        return "";
    }
}
