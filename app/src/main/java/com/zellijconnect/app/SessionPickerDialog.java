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
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SessionPickerDialog extends Dialog {

    public interface SessionPickerListener {
        void onNewSession();
        void onSessionSelected(String sessionName);
    }

    private final SessionPickerListener listener;
    private final List<String> sessions = new ArrayList<>();
    private RecyclerView sessionList;
    private ProgressBar progressBar;
    private TextView emptyText;
    private EditText sessionNameInput;
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
        Button btnAttach = findViewById(R.id.btnAttach);
        Button btnNewSession = findViewById(R.id.btnNewSession);
        Button btnCancel = findViewById(R.id.btnCancel);

        adapter = new SessionAdapter();
        sessionList.setLayoutManager(new LinearLayoutManager(getContext()));
        sessionList.setAdapter(adapter);

        btnAttach.setOnClickListener(v -> attachToInputSession());

        sessionNameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                attachToInputSession();
                return true;
            }
            return false;
        });

        btnNewSession.setOnClickListener(v -> {
            dismiss();
            listener.onNewSession();
        });

        btnCancel.setOnClickListener(v -> dismiss());

        // Start loading sessions
        showLoading(true);
    }

    private void attachToInputSession() {
        String name = sessionNameInput.getText().toString().trim();
        if (!name.isEmpty()) {
            dismiss();
            listener.onSessionSelected(name);
        }
    }

    public void setSessions(List<String> sessionNames) {
        sessions.clear();
        sessions.addAll(sessionNames);
        showLoading(false);

        if (sessions.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            sessionList.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            sessionList.setVisibility(View.VISIBLE);
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
            String session = sessions.get(position);
            holder.sessionName.setText(session);
            holder.itemView.setOnClickListener(v -> {
                dismiss();
                listener.onSessionSelected(session);
            });
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView sessionName;

            ViewHolder(View itemView) {
                super(itemView);
                sessionName = itemView.findViewById(R.id.sessionName);
            }
        }
    }
}
