package com.zellijconnect.app;

import android.content.Context;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Compound view for browsing remote directories via SFTP and viewing files.
 * Contains breadcrumb bar, directory listing, hidden-files toggle, and file viewer.
 */
public class FileBrowserView extends FrameLayout {

    private static final String TAG = "ZellijConnect";

    // Directory mode views
    private LinearLayout directoryContainer;
    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private RecyclerView fileList;
    private LinearLayoutManager layoutManager;
    private ProgressBar loadingIndicator;
    private TextView errorText;
    private TextView emptyText;
    private ImageButton btnToggleHidden;
    private ImageButton btnReload;

    // File viewer
    private FrameLayout fileViewerContainer;
    private FileViewerView fileViewerView;

    private FileBrowserAdapter adapter;
    private SftpManager sftpManager;
    private String currentPath;
    private String host;
    private int port;
    private String homeDirectory;
    private boolean viewingFile;
    private Parcelable pendingScrollState;

    public FileBrowserView(Context context) {
        super(context);
        init(context);
    }

    public FileBrowserView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_file_browser, this, true);

        directoryContainer = findViewById(R.id.directoryContainer);
        breadcrumbContainer = findViewById(R.id.breadcrumbContainer);
        breadcrumbScroll = (HorizontalScrollView) breadcrumbContainer.getParent();
        fileList = findViewById(R.id.fileList);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        errorText = findViewById(R.id.errorText);
        emptyText = findViewById(R.id.emptyText);
        btnToggleHidden = findViewById(R.id.btnToggleHidden);
        btnReload = findViewById(R.id.btnReload);
        fileViewerContainer = findViewById(R.id.fileViewerContainer);

        adapter = new FileBrowserAdapter(entry -> {
            if (entry.isDirectory) {
                navigateTo(entry.path);
            } else {
                openFile(entry.path, entry.name);
            }
        });

        layoutManager = new LinearLayoutManager(context);
        fileList.setLayoutManager(layoutManager);
        fileList.setAdapter(adapter);

        btnReload.setOnClickListener(v -> refresh());

        btnToggleHidden.setOnClickListener(v -> {
            boolean newState = !adapter.isShowingHidden();
            adapter.setShowHidden(newState);
            btnToggleHidden.setAlpha(newState ? 1.0f : 0.4f);
        });
    }

    /**
     * Configure and start browsing.
     */
    public void setup(SftpManager sftpManager, String host, int port, String initialPath, String homeDirectory) {
        this.sftpManager = sftpManager;
        this.host = host;
        this.port = port;
        this.homeDirectory = homeDirectory;
        Log.d(TAG, "FileBrowserView.setup: host=" + host + ", port=" + port + ", initialPath=" + initialPath + ", home=" + homeDirectory);
        navigateTo(initialPath);
    }

    /**
     * Navigate to a directory and load its contents.
     */
    public void navigateTo(String path) {
        this.currentPath = path;
        this.viewingFile = false;
        this.pendingScrollState = null;
        showDirectoryMode();
        updateBreadcrumb(path);
        loadDirectory(path);
    }

    /**
     * Open a file for viewing.
     */
    private void openFile(String path, String fileName) {
        // Save directory scroll position before switching to file view
        pendingScrollState = layoutManager.onSaveInstanceState();
        viewingFile = true;

        if (fileViewerView == null) {
            fileViewerView = new FileViewerView(getContext());
            fileViewerView.setup(sftpManager, host, port);
            fileViewerView.setOnBackListener(this::closeFileViewer);
            fileViewerContainer.addView(fileViewerView,
                new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ));
        }

        showFileMode();
        fileViewerView.viewFile(path, fileName);
    }

    /**
     * Close the file viewer and return to directory listing.
     */
    private void closeFileViewer() {
        viewingFile = false;
        showDirectoryMode();
        // Restore directory scroll position
        if (pendingScrollState != null) {
            layoutManager.onRestoreInstanceState(pendingScrollState);
            pendingScrollState = null;
        }
    }

    private void showDirectoryMode() {
        directoryContainer.setVisibility(View.VISIBLE);
        fileViewerContainer.setVisibility(View.GONE);
    }

    private void showFileMode() {
        directoryContainer.setVisibility(View.GONE);
        fileViewerContainer.setVisibility(View.VISIBLE);
    }

    /**
     * Refresh the current directory listing, preserving scroll position.
     */
    public void refresh() {
        if (viewingFile) {
            if (fileViewerView != null) fileViewerView.reload();
            return;
        }
        if (currentPath != null) {
            pendingScrollState = layoutManager.onSaveInstanceState();
            loadDirectory(currentPath);
        }
    }

    /**
     * Whether we're currently viewing a file (not directory listing).
     */
    public boolean isViewingFile() {
        return viewingFile;
    }

    public String getCurrentPath() {
        return currentPath;
    }

    private void loadDirectory(String path) {
        Log.d(TAG, "loadDirectory: path=" + path + ", host=" + host + ", port=" + port);
        showLoading();

        sftpManager.listDirectory(host, port, path, new SftpManager.ListCallback() {
            @Override
            public void onSuccess(List<SftpFileEntry> entries) {
                if (entries.isEmpty()) {
                    showEmpty();
                } else {
                    showList();
                    adapter.setEntries(entries);
                    if (pendingScrollState != null) {
                        layoutManager.onRestoreInstanceState(pendingScrollState);
                        pendingScrollState = null;
                    } else {
                        fileList.scrollToPosition(0);
                    }
                }
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showLoading() {
        loadingIndicator.setVisibility(View.VISIBLE);
        fileList.setVisibility(View.GONE);
        errorText.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
    }

    private void showList() {
        loadingIndicator.setVisibility(View.GONE);
        fileList.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);
        emptyText.setVisibility(View.GONE);
    }

    private void showError(String message) {
        loadingIndicator.setVisibility(View.GONE);
        fileList.setVisibility(View.GONE);
        errorText.setVisibility(View.VISIBLE);
        errorText.setText(message);
        emptyText.setVisibility(View.GONE);
    }

    private void showEmpty() {
        loadingIndicator.setVisibility(View.GONE);
        fileList.setVisibility(View.GONE);
        errorText.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
    }

    private void updateBreadcrumb(String path) {
        breadcrumbContainer.removeAllViews();

        // Check if path starts with home directory - show as ~
        String displayPath = path;
        boolean startsWithHome = homeDirectory != null && path.startsWith(homeDirectory);
        if (startsWithHome) {
            displayPath = "~" + path.substring(homeDirectory.length());
            // Add home "~" segment that navigates to home
            addBreadcrumbSegment("~", homeDirectory);
        } else {
            // Add root "/" segment
            addBreadcrumbSegment("/", "/");
        }

        // Get the part after home or root
        String remainingPath = startsWithHome ? path.substring(homeDirectory.length()) : path;
        String[] segments = remainingPath.split("/");
        StringBuilder accumulated = new StringBuilder(startsWithHome ? homeDirectory : "");

        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            accumulated.append("/").append(segment);
            String fullPath = accumulated.toString();

            // Add separator
            TextView sep = new TextView(getContext());
            sep.setText(" / ");
            sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sep.setTextColor(getResources().getColor(
                com.google.android.material.R.color.material_on_surface_emphasis_medium,
                getContext().getTheme()));
            breadcrumbContainer.addView(sep);

            // Add segment
            addBreadcrumbSegment(segment, fullPath);
        }

        // Scroll to end
        breadcrumbScroll.post(() -> breadcrumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addBreadcrumbSegment(String label, String path) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(8, 4, 8, 4);
        tv.setClickable(true);
        tv.setFocusable(true);

        // Use colorPrimary for the last (current) segment, colorOnSurface for others
        boolean isCurrent = path.equals(currentPath);
        TypedValue typedValue = new TypedValue();
        if (isCurrent) {
            getContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, typedValue, true);
            tv.setTextColor(typedValue.data);
        } else {
            getContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, typedValue, true);
            tv.setTextColor(typedValue.data);
            tv.setOnClickListener(v -> navigateTo(path));
        }

        tv.setBackgroundResource(android.R.color.transparent);
        breadcrumbContainer.addView(tv);
    }

    /**
     * Handle back navigation.
     * Returns true if handled, false if already at root directory.
     */
    public boolean navigateUp() {
        // If viewing a file, go back to directory listing
        if (viewingFile) {
            closeFileViewer();
            return true;
        }

        // Navigate up one directory
        if (currentPath == null || "/".equals(currentPath)) {
            return false;
        }
        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash <= 0) {
            navigateTo("/");
        } else {
            navigateTo(currentPath.substring(0, lastSlash));
        }
        return true;
    }
}
