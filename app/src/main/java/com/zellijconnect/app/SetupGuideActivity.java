package com.zellijconnect.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class SetupGuideActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "zellij_setup";
    private static final String KEY_SETUP_COMPLETE = "setup_complete";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_setup_guide);

        Button btnDone = findViewById(R.id.btnSetupDone);
        Button btnSkip = findViewById(R.id.btnSetupSkip);

        btnDone.setOnClickListener(v -> {
            markSetupComplete();
            launchMain();
        });

        btnSkip.setOnClickListener(v -> {
            markSetupComplete();
            launchMain();
        });
    }

    private void markSetupComplete() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .apply();
    }

    private void launchMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    public static boolean isSetupComplete(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false);
    }
}
