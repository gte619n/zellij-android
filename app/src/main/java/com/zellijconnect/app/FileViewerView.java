package com.zellijconnect.app;

import android.content.Context;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.syntax.Prism4jThemeDarkula;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

import java.nio.charset.StandardCharsets;

/**
 * View for displaying file contents with markdown rendering and syntax highlighting.
 * Supports truncation with load-more, binary detection, and scroll preservation.
 */
public class FileViewerView extends LinearLayout {

    private static final String TAG = "ZellijConnect";
    private static final int LINES_PER_PAGE = 500;

    private TextView txtFileName;
    private ImageButton btnBack;
    private ImageButton btnReloadFile;
    private ScrollView contentScroll;
    private TextView txtContent;
    private Button btnLoadMore;
    private ProgressBar fileLoadingIndicator;
    private TextView fileErrorText;
    private TextView binaryMessage;

    private Markwon markwon;
    private Prism4j prism4j;

    private SftpManager sftpManager;
    private String host;
    private int port;
    private String currentFilePath;
    private String currentFileName;
    private byte[] fullContent;        // Raw file bytes
    private String[] allLines;         // Split lines
    private int linesShown;            // How many lines currently displayed
    private Parcelable pendingScrollState;

    private OnBackListener backListener;

    public interface OnBackListener {
        void onBack();
    }

    public FileViewerView(Context context) {
        super(context);
        init(context);
    }

    public FileViewerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_file_content, this, true);

        txtFileName = findViewById(R.id.txtFileName);
        btnBack = findViewById(R.id.btnBack);
        btnReloadFile = findViewById(R.id.btnReloadFile);
        contentScroll = findViewById(R.id.contentScroll);
        txtContent = findViewById(R.id.txtContent);
        btnLoadMore = findViewById(R.id.btnLoadMore);
        fileLoadingIndicator = findViewById(R.id.fileLoadingIndicator);
        fileErrorText = findViewById(R.id.fileErrorText);
        binaryMessage = findViewById(R.id.binaryMessage);

        // Init Markwon with plugins
        try {
            prism4j = new Prism4j(new PrismGrammarLocator());
            markwon = Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(TaskListPlugin.create(context))
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDarkula.create()))
                .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to init Markwon with syntax highlighting, using basic", e);
            markwon = Markwon.create(context);
        }

        btnBack.setOnClickListener(v -> {
            if (backListener != null) backListener.onBack();
        });

        btnReloadFile.setOnClickListener(v -> reload());

        btnLoadMore.setOnClickListener(v -> loadMoreLines());
    }

    public void setup(SftpManager sftpManager, String host, int port) {
        this.sftpManager = sftpManager;
        this.host = host;
        this.port = port;
    }

    public void setOnBackListener(OnBackListener listener) {
        this.backListener = listener;
    }

    /**
     * Load and display a file.
     */
    public void viewFile(String path, String fileName) {
        this.currentFilePath = path;
        this.currentFileName = fileName;
        this.pendingScrollState = null;
        txtFileName.setText(fileName);
        loadFile(path);
    }

    /**
     * Reload the current file, preserving scroll position.
     */
    public void reload() {
        if (currentFilePath != null) {
            if (contentScroll.getVisibility() == View.VISIBLE) {
                pendingScrollState = new android.os.Bundle();
                ((android.os.Bundle) pendingScrollState).putIntArray("scroll",
                    new int[]{contentScroll.getScrollX(), contentScroll.getScrollY()});
            }
            loadFile(currentFilePath);
        }
    }

    public String getCurrentFilePath() {
        return currentFilePath;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    private void loadFile(String path) {
        showLoading();

        sftpManager.readFile(host, port, path, new SftpManager.ContentCallback() {
            @Override
            public void onSuccess(byte[] content) {
                fullContent = content;
                renderContent(content);
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void renderContent(byte[] content) {
        // Check for binary content
        if (FileTypeDetector.isBinaryContent(content)) {
            showBinary(content.length);
            return;
        }

        String text = new String(content, StandardCharsets.UTF_8);
        allLines = text.split("\n", -1);

        FileTypeDetector.FileType fileType = FileTypeDetector.detect(currentFileName);

        if (fileType == FileTypeDetector.FileType.MARKDOWN) {
            renderMarkdown(text);
        } else if (fileType == FileTypeDetector.FileType.SOURCE_CODE) {
            renderSourceCode(text, currentFileName);
        } else {
            renderPlainText(text);
        }
    }

    private void renderMarkdown(String text) {
        String displayText = truncateIfNeeded(text);
        showContent();
        markwon.setMarkdown(txtContent, displayText);
        restoreScroll();
    }

    private void renderSourceCode(String text, String fileName) {
        String displayText = truncateIfNeeded(text);
        String language = FileTypeDetector.getLanguage(fileName);

        // Wrap in markdown code fence for Markwon syntax highlighting
        String fenced;
        if (language != null) {
            fenced = "```" + language + "\n" + displayText + "\n```";
        } else {
            fenced = "```\n" + displayText + "\n```";
        }

        showContent();
        markwon.setMarkdown(txtContent, fenced);
        restoreScroll();
    }

    private void renderPlainText(String text) {
        String displayText = truncateIfNeeded(text);
        showContent();
        txtContent.setText(displayText);
        restoreScroll();
    }

    private String truncateIfNeeded(String text) {
        if (allLines.length <= LINES_PER_PAGE) {
            linesShown = allLines.length;
            btnLoadMore.setVisibility(View.GONE);
            return text;
        }

        linesShown = LINES_PER_PAGE;
        btnLoadMore.setVisibility(View.VISIBLE);
        btnLoadMore.setText(getContext().getString(R.string.lines_truncated, linesShown, allLines.length));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < linesShown; i++) {
            if (i > 0) sb.append('\n');
            sb.append(allLines[i]);
        }
        return sb.toString();
    }

    private void loadMoreLines() {
        if (allLines == null) return;

        int newEnd = Math.min(linesShown + LINES_PER_PAGE, allLines.length);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newEnd; i++) {
            if (i > 0) sb.append('\n');
            sb.append(allLines[i]);
        }

        linesShown = newEnd;
        String displayText = sb.toString();

        FileTypeDetector.FileType fileType = FileTypeDetector.detect(currentFileName);

        if (fileType == FileTypeDetector.FileType.MARKDOWN) {
            markwon.setMarkdown(txtContent, displayText);
        } else if (fileType == FileTypeDetector.FileType.SOURCE_CODE) {
            String language = FileTypeDetector.getLanguage(currentFileName);
            String fenced = language != null ?
                "```" + language + "\n" + displayText + "\n```" :
                "```\n" + displayText + "\n```";
            markwon.setMarkdown(txtContent, fenced);
        } else {
            txtContent.setText(displayText);
        }

        if (linesShown >= allLines.length) {
            btnLoadMore.setVisibility(View.GONE);
        } else {
            btnLoadMore.setText(getContext().getString(R.string.lines_truncated, linesShown, allLines.length));
        }
    }

    private void restoreScroll() {
        if (pendingScrollState instanceof android.os.Bundle) {
            int[] scroll = ((android.os.Bundle) pendingScrollState).getIntArray("scroll");
            if (scroll != null && scroll.length == 2) {
                contentScroll.post(() -> contentScroll.scrollTo(scroll[0], scroll[1]));
            }
            pendingScrollState = null;
        }
    }

    private void showLoading() {
        fileLoadingIndicator.setVisibility(View.VISIBLE);
        contentScroll.setVisibility(View.GONE);
        fileErrorText.setVisibility(View.GONE);
        binaryMessage.setVisibility(View.GONE);
    }

    private void showContent() {
        fileLoadingIndicator.setVisibility(View.GONE);
        contentScroll.setVisibility(View.VISIBLE);
        fileErrorText.setVisibility(View.GONE);
        binaryMessage.setVisibility(View.GONE);
    }

    private void showError(String message) {
        fileLoadingIndicator.setVisibility(View.GONE);
        contentScroll.setVisibility(View.GONE);
        fileErrorText.setVisibility(View.VISIBLE);
        fileErrorText.setText(message);
        binaryMessage.setVisibility(View.GONE);
    }

    private void showBinary(int sizeBytes) {
        fileLoadingIndicator.setVisibility(View.GONE);
        contentScroll.setVisibility(View.GONE);
        fileErrorText.setVisibility(View.GONE);
        binaryMessage.setVisibility(View.VISIBLE);
        binaryMessage.setText(getContext().getString(R.string.binary_file,
            SftpFileEntry.humanReadableSize(sizeBytes)));
    }
}
