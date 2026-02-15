package com.zellijconnect.app;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SessionPickerDialog extends Dialog {

    public interface SessionPickerListener {
        void onGateway();
        void onCreateSession(String sessionName);
        void onAttachSession(String sessionName);
    }

    private final SessionPickerListener listener;
    private final List<SessionInfo> sessions = new ArrayList<>();
    private RecyclerView sessionList;
    private ProgressBar progressBar;
    private TextView emptyText;
    private EditText sessionNameInput;
    private LinearLayout tableHeader;
    private View headerDivider;
    private SessionAdapter adapter;

    public SessionPickerDialog(@NonNull Context context, SessionPickerListener listener) {
        super(context);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_session_picker);

        sessionList = findViewById(R.id.sessionList);
        progressBar = findViewById(R.id.progressBar);
        emptyText = findViewById(R.id.emptyText);
        sessionNameInput = findViewById(R.id.sessionNameInput);
        tableHeader = findViewById(R.id.tableHeader);
        headerDivider = findViewById(R.id.headerDivider);
        Button btnGateway = findViewById(R.id.btnGateway);
        Button btnCreate = findViewById(R.id.btnCreate);
        Button btnCancel = findViewById(R.id.btnCancel);

        adapter = new SessionAdapter();
        sessionList.setLayoutManager(new LinearLayoutManager(getContext()));
        sessionList.setAdapter(adapter);

        btnGateway.setOnClickListener(v -> {
            dismiss();
            listener.onGateway();
        });

        btnCreate.setOnClickListener(v -> createSession());

        sessionNameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                createSession();
                return true;
            }
            return false;
        });

        btnCancel.setOnClickListener(v -> dismiss());

        // Show loading state
        showLoading(true);
    }

    private void createSession() {
        String name = sessionNameInput.getText().toString().trim();
        if (!name.isEmpty()) {
            dismiss();
            listener.onCreateSession(name);
        }
    }

    public void setSessions(List<SessionInfo> sessionInfos) {
        sessions.clear();
        sessions.addAll(sessionInfos);
        showLoading(false);

        if (sessions.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            sessionList.setVisibility(View.GONE);
            tableHeader.setVisibility(View.GONE);
            headerDivider.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            sessionList.setVisibility(View.VISIBLE);
            tableHeader.setVisibility(View.VISIBLE);
            headerDivider.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    public void showError(String message) {
        showLoading(false);
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
        sessionList.setVisibility(View.GONE);
    }

    private void showLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            sessionList.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
        }
    }

    private class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SessionInfo session = sessions.get(position);
            Context ctx = holder.itemView.getContext();

            // Session name
            holder.sessionName.setText(session.name);

            // Claude status icon
            int iconRes;
            switch (session.claudeStatus) {
                case "working":
                    iconRes = R.drawable.ic_claude_working;
                    break;
                case "waiting":
                    iconRes = R.drawable.ic_claude_waiting;
                    break;
                case "done":
                    iconRes = R.drawable.ic_claude_done;
                    break;
                case "idle":
                default:
                    iconRes = R.drawable.ic_claude_idle;
            }
            holder.claudeStatusIcon.setImageResource(iconRes);

            // Status text / activity summary
            String statusText = session.claudeActivity;
            if (statusText == null || statusText.isEmpty()) {
                switch (session.claudeStatus) {
                    case "working":
                        statusText = ctx.getString(R.string.status_working);
                        break;
                    case "waiting":
                        statusText = ctx.getString(R.string.status_waiting);
                        break;
                    case "done":
                        statusText = "Completed";
                        break;
                    case "idle":
                        statusText = ctx.getString(R.string.status_idle);
                        break;
                    default:
                        statusText = session.claudeStatus;
                }
            }
            holder.statusText.setText(statusText);

            // Git branch
            if (session.gitBranch != null && !session.gitBranch.isEmpty()) {
                holder.gitBranch.setText(session.gitBranch);
                holder.gitBranch.setVisibility(View.VISIBLE);
            } else {
                holder.gitBranch.setVisibility(View.GONE);
            }

            // Merged badge
            if (session.mergedToDev) {
                holder.mergedBadge.setVisibility(View.VISIBLE);
            } else {
                holder.mergedBadge.setVisibility(View.GONE);
            }

            // Click to attach
            holder.itemView.setOnClickListener(v -> {
                dismiss();
                listener.onAttachSession(session.name);
            });
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView claudeStatusIcon;
            final TextView sessionName;
            final TextView statusText;
            final TextView gitBranch;
            final TextView mergedBadge;

            ViewHolder(View itemView) {
                super(itemView);
                claudeStatusIcon = itemView.findViewById(R.id.claudeStatusIcon);
                sessionName = itemView.findViewById(R.id.sessionName);
                statusText = itemView.findViewById(R.id.statusText);
                gitBranch = itemView.findViewById(R.id.gitBranch);
                mergedBadge = itemView.findViewById(R.id.mergedBadge);
            }
        }
    }
}
