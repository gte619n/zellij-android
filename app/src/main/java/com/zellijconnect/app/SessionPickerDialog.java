package com.zellijconnect.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SessionPickerDialog extends Dialog {

    public interface SessionPickerListener {
        void onGateway();
        void onCreateSession(String sessionName);
        void onAttachSession(String sessionName);
        void onDeleteSession(String sessionName, boolean deleteWorktree, boolean deleteBranch);
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

        // Swipe-to-delete on session rows
        ItemTouchHelper swipeTouchHelper = new ItemTouchHelper(
            new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                private final ColorDrawable background = new ColorDrawable(Color.parseColor("#D32F2F"));
                private final Drawable deleteIcon = ContextCompat.getDrawable(getContext(), R.drawable.ic_delete);

                @Override
                public boolean onMove(@NonNull RecyclerView rv,
                                      @NonNull RecyclerView.ViewHolder vh,
                                      @NonNull RecyclerView.ViewHolder target) {
                    return false;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                    int position = vh.getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && position < sessions.size()) {
                        SessionInfo session = sessions.get(position);
                        showKillConfirmation(session, position);
                    }
                }

                @Override
                public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                                        @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                        int actionState, boolean isCurrentlyActive) {
                    View itemView = vh.itemView;

                    if (dX < 0) {
                        // Draw red background
                        background.setBounds(
                            itemView.getRight() + (int) dX,
                            itemView.getTop(),
                            itemView.getRight(),
                            itemView.getBottom()
                        );
                        background.draw(c);

                        // Draw trash icon centered vertically on right side
                        if (deleteIcon != null) {
                            int iconMargin = (itemView.getHeight() - deleteIcon.getIntrinsicHeight()) / 2;
                            int iconTop = itemView.getTop() + iconMargin;
                            int iconBottom = iconTop + deleteIcon.getIntrinsicHeight();
                            int iconLeft = itemView.getRight() - iconMargin - deleteIcon.getIntrinsicWidth();
                            int iconRight = itemView.getRight() - iconMargin;
                            deleteIcon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                            deleteIcon.draw(c);
                        }
                    }

                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
                }
            }
        );
        swipeTouchHelper.attachToRecyclerView(sessionList);

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

    private void showKillConfirmation(SessionInfo session, int position) {
        Context ctx = getContext();
        StringBuilder message = new StringBuilder();
        message.append("Session: ").append(session.name).append("\n");

        if (session.gitBranch != null && !session.gitBranch.isEmpty()) {
            message.append("Branch: ").append(session.gitBranch).append("\n");
        }

        message.append("\n");

        // Git warnings
        boolean hasWarnings = false;
        if (session.hasUncommittedChanges) {
            message.append(ctx.getString(R.string.warning_uncommitted)).append("\n");
            hasWarnings = true;
        }
        if (session.unpushedCommitCount > 0) {
            message.append(String.format(ctx.getString(R.string.warning_unpushed), session.unpushedCommitCount)).append("\n");
            hasWarnings = true;
        }
        if (session.gitBranch != null && !session.mergedToDev) {
            message.append(ctx.getString(R.string.warning_not_merged)).append("\n");
            hasWarnings = true;
        }
        if (session.mergedToDev) {
            message.append(ctx.getString(R.string.info_merged)).append("\n");
        }

        if (!hasWarnings && session.gitBranch == null) {
            message.append("No git information available.\n");
        }

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.kill_session_title)
            .setMessage(message.toString().trim())
            .setNegativeButton(R.string.cancel, (d, w) -> {
                // Reset the swipe — restore the row
                adapter.notifyItemChanged(position);
            })
            .setPositiveButton(R.string.kill_session_confirm, (d, w) -> {
                // Kill without worktree cleanup first
                listener.onDeleteSession(session.name, false, false);

                // If this session has a worktree, offer cleanup after kill
                boolean hasWorktree = session.workingDirectory != null
                    && session.workingDirectory.contains("/.worktrees/");
                if (hasWorktree) {
                    showWorktreeCleanup(session);
                }
            })
            .setOnCancelListener(d -> {
                // Reset the swipe if dialog is cancelled (back button)
                adapter.notifyItemChanged(position);
            })
            .show();
    }

    private void showWorktreeCleanup(SessionInfo session) {
        Context ctx = getContext();
        StringBuilder message = new StringBuilder();
        message.append(ctx.getString(R.string.cleanup_worktree_message)).append("\n\n");

        if (session.workingDirectory != null) {
            message.append("Path: ").append(session.workingDirectory).append("\n");
        }
        if (session.gitBranch != null) {
            message.append("Branch: ").append(session.gitBranch);
        }

        new MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.cleanup_worktree_title)
            .setMessage(message.toString().trim())
            .setNegativeButton(R.string.keep, null)
            .setPositiveButton(R.string.delete_worktree_branch, (d, w) -> {
                listener.onDeleteSession(session.name, true, true);
            })
            .show();
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
