package com.zellijconnect.app;

import android.content.Context;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Compound view for browsing remote directories via SFTP.
 * Contains breadcrumb bar, directory listing, and hidden-files toggle.
 */
public class FileBrowserView extends LinearLayout {

    private static final String TAG = "ZellijConnect";

    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private RecyclerView fileList;
    private LinearLayoutManager layoutManager;
    private ProgressBar loadingIndicator;
    private TextView errorText;
    private TextView emptyText;
    private ImageButton btnToggleHidden;
    private ImageButton btnReload;

    private FileBrowserAdapter adapter;
    private SftpManager sftpManager;
    private String currentPath;
    private String host;
    private int port;
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

        breadcrumbContainer = findViewById(R.id.breadcrumbContainer);
        breadcrumbScroll = (HorizontalScrollView) breadcrumbContainer.getParent();
        fileList = findViewById(R.id.fileList);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        errorText = findViewById(R.id.errorText);
        emptyText = findViewById(R.id.emptyText);
        btnToggleHidden = findViewById(R.id.btnToggleHidden);
        btnReload = findViewById(R.id.btnReload);

        adapter = new FileBrowserAdapter(entry -> {
            if (entry.isDirectory) {
                navigateTo(entry.path);
            }
            // File viewing will be added in Phase 2
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
    public void setup(SftpManager sftpManager, String host, int port, String initialPath) {
        this.sftpManager = sftpManager;
        this.host = host;
        this.port = port;
        navigateTo(initialPath);
    }

    /**
     * Navigate to a directory and load its contents (resets scroll to top).
     */
    public void navigateTo(String path) {
        this.currentPath = path;
        this.pendingScrollState = null; // New directory: scroll to top
        updateBreadcrumb(path);
        loadDirectory(path);
    }

    /**
     * Refresh the current directory listing, preserving scroll position.
     */
    public void refresh() {
        if (currentPath != null) {
            // Save scroll position before reloading
            pendingScrollState = layoutManager.onSaveInstanceState();
            loadDirectory(currentPath);
        }
    }

    public String getCurrentPath() {
        return currentPath;
    }

    private void loadDirectory(String path) {
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
                        // Restore saved scroll position (refresh case)
                        layoutManager.onRestoreInstanceState(pendingScrollState);
                        pendingScrollState = null;
                    } else {
                        // New directory navigation: scroll to top
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

        String[] segments = path.split("/");
        StringBuilder accumulated = new StringBuilder();

        // Add root "/"
        addBreadcrumbSegment("/", "/");

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
     * Handle back navigation - go up one directory.
     * Returns true if handled, false if already at root.
     */
    public boolean navigateUp() {
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
