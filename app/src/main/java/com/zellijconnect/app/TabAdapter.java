package com.zellijconnect.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class TabAdapter extends RecyclerView.Adapter<TabAdapter.ViewHolder> {

    private final TabManager tabManager;
    private final OnTabClickListener clickListener;

    public interface OnTabClickListener {
        void onTabClick(int position);
    }

    public TabAdapter(TabManager tabManager, OnTabClickListener clickListener) {
        this.tabManager = tabManager;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.tab_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TabManager.Tab tab = tabManager.getTabAt(position);
        if (tab == null) return;

        holder.label.setText(tab.label);

        boolean isActive = tab.id.equals(
            tabManager.getActiveTab() != null ? tabManager.getActiveTab().id : null
        );

        holder.activeIndicator.setVisibility(isActive ? View.VISIBLE : View.GONE);
        holder.label.setAlpha(isActive ? 1.0f : 0.6f);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                clickListener.onTabClick(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tabManager.getTabCount();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final View activeIndicator;

        ViewHolder(View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.tabLabel);
            activeIndicator = itemView.findViewById(R.id.activeIndicator);
        }
    }
}
