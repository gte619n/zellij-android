package com.zellijconnect.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserAdapter.ViewHolder> {

    private List<SftpFileEntry> allEntries = new ArrayList<>();
    private List<SftpFileEntry> visibleEntries = new ArrayList<>();
    private boolean showHidden = false;
    private final OnEntryClickListener listener;

    public interface OnEntryClickListener {
        void onEntryClick(SftpFileEntry entry);
    }

    public FileBrowserAdapter(OnEntryClickListener listener) {
        this.listener = listener;
    }

    public void setEntries(List<SftpFileEntry> entries) {
        this.allEntries = entries;
        filterEntries();
    }

    public void setShowHidden(boolean show) {
        this.showHidden = show;
        filterEntries();
    }

    public boolean isShowingHidden() {
        return showHidden;
    }

    private void filterEntries() {
        visibleEntries = new ArrayList<>();
        for (SftpFileEntry entry : allEntries) {
            if (showHidden || !entry.isHidden()) {
                visibleEntries.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SftpFileEntry entry = visibleEntries.get(position);

        holder.fileName.setText(entry.name);
        holder.fileSize.setText(entry.getHumanSize());
        holder.fileDate.setText(entry.getFormattedDate());

        if (entry.isDirectory) {
            holder.fileIcon.setImageResource(R.drawable.ic_folder);
            holder.fileName.setAlpha(1.0f);
        } else if (FileTypeDetector.detect(entry.name) == FileTypeDetector.FileType.IMAGE) {
            holder.fileIcon.setImageResource(R.drawable.ic_file_image);
            holder.fileName.setAlpha(0.9f);
        } else {
            holder.fileIcon.setImageResource(R.drawable.ic_file_generic);
            holder.fileName.setAlpha(0.9f);
        }

        // Dim hidden files slightly
        float alpha = entry.isHidden() ? 0.6f : 1.0f;
        holder.itemView.setAlpha(alpha);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEntryClick(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return visibleEntries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView fileIcon;
        final TextView fileName;
        final TextView fileSize;
        final TextView fileDate;

        ViewHolder(View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.fileIcon);
            fileName = itemView.findViewById(R.id.fileName);
            fileSize = itemView.findViewById(R.id.fileSize);
            fileDate = itemView.findViewById(R.id.fileDate);
        }
    }
}
