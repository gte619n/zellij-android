package com.zellijconnect.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Parcelable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.syntax.Prism4jThemeDarkula;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * View for displaying file contents with markdown rendering, syntax highlighting,
 * image viewing with zoom/pan, copy/share actions, and large file confirmation.
 */
public class FileViewerView extends LinearLayout {

    private static final String TAG = "ZellijConnect";
    private static final int LINES_PER_PAGE = 500;
    private static final long LARGE_IMAGE_THRESHOLD = 5 * 1024 * 1024; // 5MB

    // Toolbar
    private TextView txtFileName;
    private TextView txtFileSize;
    private ImageButton btnBack;
    private ImageButton btnReloadFile;
    private ImageButton btnCopy;
    private ImageButton btnShare;

    // Text content
    private ScrollView contentScroll;
    private TextView txtContent;
    private Button btnLoadMore;

    // Image content
    private FrameLayout imageContainer;
    private ImageView imageView;

    // Large file confirmation
    private LinearLayout largeFileConfirm;
    private TextView txtLargeFileMessage;
    private Button btnConfirmDownload;

    // States
    private ProgressBar fileLoadingIndicator;
    private TextView fileErrorText;
    private TextView binaryMessage;

    // Rendering
    private Markwon markwon;
    private Prism4j prism4j;

    // Data
    private SftpManager sftpManager;
    private String host;
    private int port;
    private String currentFilePath;
    private String currentFileName;
    private long currentFileSize;
    private byte[] fullContent;
    private String[] allLines;
    private int linesShown;
    private Parcelable pendingScrollState;
    private boolean isImageFile;
    private Bitmap currentBitmap;

    // Image zoom/pan
    private Matrix imageMatrix = new Matrix();
    private float scaleFactor = 1.0f;
    private float translateX = 0f;
    private float translateY = 0f;
    private float lastTouchX;
    private float lastTouchY;
    private ScaleGestureDetector scaleDetector;

    private OnBackListener backListener;

    public interface OnBackListener {
        void onBack();
    }

    public FileViewerView(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_file_content, this, true);

        txtFileName = findViewById(R.id.txtFileName);
        txtFileSize = findViewById(R.id.txtFileSize);
        btnBack = findViewById(R.id.btnBack);
        btnReloadFile = findViewById(R.id.btnReloadFile);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        contentScroll = findViewById(R.id.contentScroll);
        txtContent = findViewById(R.id.txtContent);
        btnLoadMore = findViewById(R.id.btnLoadMore);
        imageContainer = findViewById(R.id.imageContainer);
        imageView = findViewById(R.id.imageView);
        largeFileConfirm = findViewById(R.id.largeFileConfirm);
        txtLargeFileMessage = findViewById(R.id.txtLargeFileMessage);
        btnConfirmDownload = findViewById(R.id.btnConfirmDownload);
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

        // Button listeners
        btnBack.setOnClickListener(v -> {
            if (backListener != null) backListener.onBack();
        });
        btnReloadFile.setOnClickListener(v -> reload());
        btnLoadMore.setOnClickListener(v -> loadMoreLines());
        btnCopy.setOnClickListener(v -> copyToClipboard());
        btnShare.setOnClickListener(v -> shareContent());

        // Image zoom/pan
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 5.0f));
                updateImageMatrix();
                return true;
            }
        });

        imageContainer.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        translateX += dx;
                        translateY += dy;
                        updateImageMatrix();
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    break;
            }
            return true;
        });
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
        this.currentFileSize = 0;
        this.pendingScrollState = null;
        this.isImageFile = false;
        this.fullContent = null;
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }

        txtFileName.setText(fileName);

        FileTypeDetector.FileType fileType = FileTypeDetector.detect(fileName);
        if (fileType == FileTypeDetector.FileType.IMAGE) {
            isImageFile = true;
            // Check file size first before downloading
            checkImageSize(path, fileName);
        } else {
            loadFile(path);
        }
    }

    /**
     * Check image size and prompt if large.
     */
    private void checkImageSize(String path, String fileName) {
        showLoading();
        sftpManager.statFile(host, port, path, new SftpManager.StatCallback() {
            @Override
            public void onSuccess(long sizeBytes) {
                currentFileSize = sizeBytes;
                showFileSize(sizeBytes);
                if (sizeBytes > LARGE_IMAGE_THRESHOLD) {
                    showLargeFileConfirm(sizeBytes);
                } else {
                    loadFile(path);
                }
            }

            @Override
            public void onError(String message) {
                // Can't stat — just try loading it
                loadFile(path);
            }
        });
    }

    private void showLargeFileConfirm(long sizeBytes) {
        hideAllContent();
        largeFileConfirm.setVisibility(View.VISIBLE);
        txtLargeFileMessage.setText(getContext().getString(
            R.string.large_image_confirm,
            SftpFileEntry.humanReadableSize(sizeBytes)));
        btnConfirmDownload.setOnClickListener(v -> loadFile(currentFilePath));
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
            if (isImageFile) {
                checkImageSize(currentFilePath, currentFileName);
            } else {
                loadFile(currentFilePath);
            }
        }
    }

    public String getCurrentFilePath() { return currentFilePath; }
    public String getCurrentFileName() { return currentFileName; }

    private void loadFile(String path) {
        showLoading();

        sftpManager.readFile(host, port, path, new SftpManager.ContentCallback() {
            @Override
            public void onSuccess(byte[] content) {
                fullContent = content;
                currentFileSize = content.length;
                showFileSize(content.length);

                if (isImageFile) {
                    renderImage(content);
                } else {
                    renderContent(content);
                }
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void renderContent(byte[] content) {
        if (FileTypeDetector.isBinaryContent(content)) {
            showBinary(content.length);
            return;
        }

        String text = new String(content, StandardCharsets.UTF_8);
        allLines = text.split("\n", -1);

        // Show copy/share for text
        btnCopy.setVisibility(View.VISIBLE);
        btnShare.setVisibility(View.VISIBLE);

        FileTypeDetector.FileType fileType = FileTypeDetector.detect(currentFileName);

        if (fileType == FileTypeDetector.FileType.MARKDOWN) {
            renderMarkdown(text);
        } else if (fileType == FileTypeDetector.FileType.SOURCE_CODE) {
            renderSourceCode(text, currentFileName);
        } else {
            renderPlainText(text);
        }
    }

    private void renderImage(byte[] content) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            // Decode bounds first for large images
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(content, 0, content.length, opts);

            // Calculate sample size for very large images
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (maxDim / sampleSize > 4096) {
                sampleSize *= 2;
            }

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeByteArray(content, 0, content.length, opts);

            if (bitmap == null) {
                showError(getContext().getString(R.string.image_load_failed));
                return;
            }

            if (currentBitmap != null) currentBitmap.recycle();
            currentBitmap = bitmap;

            // Reset zoom/pan
            scaleFactor = 1.0f;
            translateX = 0f;
            translateY = 0f;

            showImage();
            imageView.setImageBitmap(bitmap);

            // Show share button for images
            btnCopy.setVisibility(View.GONE);
            btnShare.setVisibility(View.VISIBLE);

        } catch (Exception e) {
            Log.e(TAG, "Failed to decode image", e);
            showError(getContext().getString(R.string.image_load_failed));
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

    // --- Copy / Share ---

    private void copyToClipboard() {
        if (fullContent == null) return;
        String text = new String(fullContent, StandardCharsets.UTF_8);
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(currentFileName, text));
        Toast.makeText(getContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void shareContent() {
        if (fullContent == null) return;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);

        if (isImageFile && currentBitmap != null) {
            // Share image via content URI
            try {
                File cacheDir = new File(getContext().getCacheDir(), "shared");
                cacheDir.mkdirs();
                File file = new File(cacheDir, currentFileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(fullContent);
                fos.close();

                android.net.Uri uri = FileProvider.getUriForFile(getContext(),
                    getContext().getPackageName() + ".fileprovider", file);
                shareIntent.setType(getMimeType(currentFileName));
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Log.e(TAG, "Failed to share image", e);
                Toast.makeText(getContext(), "Failed to share: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            // Share text content
            String text = new String(fullContent, StandardCharsets.UTF_8);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentFileName);
        }

        getContext().startActivity(Intent.createChooser(shareIntent, null));
    }

    private String getMimeType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            default: return "application/octet-stream";
        }
    }

    // --- Image zoom/pan ---

    private void updateImageMatrix() {
        imageMatrix.reset();
        imageMatrix.postScale(scaleFactor, scaleFactor,
            imageContainer.getWidth() / 2f, imageContainer.getHeight() / 2f);
        imageMatrix.postTranslate(translateX, translateY);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageView.setImageMatrix(imageMatrix);
    }

    // --- Scroll restoration ---

    private void restoreScroll() {
        if (pendingScrollState instanceof android.os.Bundle) {
            int[] scroll = ((android.os.Bundle) pendingScrollState).getIntArray("scroll");
            if (scroll != null && scroll.length == 2) {
                contentScroll.post(() -> contentScroll.scrollTo(scroll[0], scroll[1]));
            }
            pendingScrollState = null;
        }
    }

    // --- File size display ---

    private void showFileSize(long sizeBytes) {
        txtFileSize.setText(SftpFileEntry.humanReadableSize(sizeBytes));
        txtFileSize.setVisibility(View.VISIBLE);
    }

    // --- Visibility helpers ---

    private void hideAllContent() {
        fileLoadingIndicator.setVisibility(View.GONE);
        contentScroll.setVisibility(View.GONE);
        imageContainer.setVisibility(View.GONE);
        largeFileConfirm.setVisibility(View.GONE);
        fileErrorText.setVisibility(View.GONE);
        binaryMessage.setVisibility(View.GONE);
    }

    private void showLoading() {
        hideAllContent();
        fileLoadingIndicator.setVisibility(View.VISIBLE);
        btnCopy.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
    }

    private void showContent() {
        hideAllContent();
        contentScroll.setVisibility(View.VISIBLE);
    }

    private void showImage() {
        hideAllContent();
        imageContainer.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        hideAllContent();
        fileErrorText.setVisibility(View.VISIBLE);
        fileErrorText.setText(message);
        btnCopy.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
    }

    private void showBinary(int sizeBytes) {
        hideAllContent();
        binaryMessage.setVisibility(View.VISIBLE);
        binaryMessage.setText(getContext().getString(R.string.binary_file,
            SftpFileEntry.humanReadableSize(sizeBytes)));
        btnCopy.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
    }
}
