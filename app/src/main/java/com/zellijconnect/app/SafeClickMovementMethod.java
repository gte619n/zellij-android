package com.zellijconnect.app;

import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * A LinkMovementMethod that catches exceptions caused by stale span indices
 * during async span replacement (e.g., mermaid diagram rendering).
 */
public class SafeClickMovementMethod extends LinkMovementMethod {

    private static SafeClickMovementMethod sInstance;

    public static SafeClickMovementMethod getInstance() {
        if (sInstance == null) {
            sInstance = new SafeClickMovementMethod();
        }
        return sInstance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, android.text.Spannable buffer, MotionEvent event) {
        try {
            return super.onTouchEvent(widget, buffer, event);
        } catch (Exception e) {
            // Stale span indices from async replacement — swallow the exception
            return false;
        }
    }
}
