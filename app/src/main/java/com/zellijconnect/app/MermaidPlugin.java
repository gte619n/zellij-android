package com.zellijconnect.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.commonmark.node.FencedCodeBlock;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonVisitor;

/**
 * Markwon plugin that intercepts ```mermaid fenced code blocks and renders
 * them as inline diagram images using MermaidRenderer (WebView + mermaid.js).
 */
public class MermaidPlugin extends AbstractMarkwonPlugin {

    public interface OnDiagramClickListener {
        void onDiagramClick(Bitmap bitmap);
    }

    private final MermaidRenderer renderer;
    private final MermaidBitmapCache cache;
    private final Context context;
    private OnDiagramClickListener diagramClickListener;

    // Pending diagrams from the most recent setMarkdown pass
    private final List<PendingDiagram> pendingDiagrams = new ArrayList<>();

    private static class PendingDiagram {
        final String mermaidCode;
        final String cacheKey;
        final ImageSpan placeholderSpan;

        PendingDiagram(String code, String key, ImageSpan span) {
            this.mermaidCode = code;
            this.cacheKey = key;
            this.placeholderSpan = span;
        }
    }

    public MermaidPlugin(Context context, MermaidRenderer renderer, MermaidBitmapCache cache) {
        this.context = context.getApplicationContext();
        this.renderer = renderer;
        this.cache = cache;
    }

    public static MermaidPlugin create(Context context) {
        MermaidBitmapCache cache = new MermaidBitmapCache();
        MermaidRenderer renderer = new MermaidRenderer(context, cache);
        return new MermaidPlugin(context, renderer, cache);
    }

    public void setOnDiagramClickListener(OnDiagramClickListener listener) {
        this.diagramClickListener = listener;
    }

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        builder.on(FencedCodeBlock.class, (visitor, node) -> {
            String info = node.getInfo() != null ? node.getInfo().trim() : "";

            if ("mermaid".equalsIgnoreCase(info)) {
                handleMermaidBlock(visitor, node);
            } else {
                handleDefaultCodeBlock(visitor, node);
            }
        });
    }

    private void handleMermaidBlock(MarkwonVisitor visitor, FencedCodeBlock node) {
        String mermaidCode = node.getLiteral();
        if (mermaidCode == null || mermaidCode.trim().isEmpty()) return;

        String cacheKey = String.valueOf(mermaidCode.hashCode());
        int length = visitor.length();

        // Emit the object replacement character for the image span
        visitor.builder().append('\uFFFC');

        Bitmap cached = cache.get(cacheKey);
        if (cached != null) {
            // Cache hit — use the rendered bitmap directly
            float density = context.getResources().getDisplayMetrics().density;
            int drawW = Math.round(cached.getWidth() / density);
            int drawH = Math.round(cached.getHeight() / density);
            BitmapDrawable drawable = new BitmapDrawable(context.getResources(), cached);
            drawable.setBounds(0, 0, drawW, drawH);
            visitor.builder().setSpan(
                    new ImageSpan(drawable),
                    length,
                    length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            final Bitmap clickBitmap = cached;
            visitor.builder().setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    if (diagramClickListener != null) diagramClickListener.onDiagramClick(clickBitmap);
                }
                @Override
                public void updateDrawState(@NonNull android.text.TextPaint ds) {}
            }, length, length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            // Cache miss — show placeholder, queue for async rendering
            Drawable placeholder = createPlaceholderDrawable();
            ImageSpan placeholderSpan = new ImageSpan(placeholder);
            visitor.builder().setSpan(
                    placeholderSpan,
                    length,
                    length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            pendingDiagrams.add(new PendingDiagram(mermaidCode, cacheKey, placeholderSpan));
        }

        // Add a newline after the diagram
        visitor.builder().append('\n');
    }

    private void handleDefaultCodeBlock(MarkwonVisitor visitor, FencedCodeBlock node) {
        int length = visitor.length();
        visitor.visitChildren(node);

        String literal = node.getLiteral();
        if (literal != null) {
            if (visitor.length() == length) {
                visitor.builder().append(literal);
            }
        }

        visitor.setSpansForNodeOptional(node, length);

        if (visitor.hasNext(node)) {
            visitor.ensureNewLine();
        }
    }

    /**
     * Call this AFTER markwon.setMarkdown() to trigger async rendering of
     * any mermaid diagrams that weren't in cache.
     */
    public void renderPendingDiagrams(TextView textView) {
        if (pendingDiagrams.isEmpty()) return;

        List<PendingDiagram> toRender = new ArrayList<>(pendingDiagrams);
        pendingDiagrams.clear();

        // Set max width BEFORE starting renders, then kick off rendering
        textView.post(() -> {
            int width = textView.getWidth() - textView.getPaddingLeft() - textView.getPaddingRight();
            if (width > 0) {
                renderer.setMaxWidth(width);
            }

            for (PendingDiagram pending : toRender) {
                renderer.render(pending.mermaidCode, new MermaidRenderer.Callback() {
                    @Override
                    public void onRendered(Bitmap bitmap) {
                        replacePlaceholder(textView, pending.placeholderSpan, bitmap);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        replaceWithError(textView, pending.placeholderSpan, errorMessage);
                    }
                });
            }
        });
    }

    /** Clear pending state (call before each new setMarkdown). */
    public void clearPending() {
        pendingDiagrams.clear();
    }

    /** Destroy the renderer. Call when the view is detached. */
    public void destroy() {
        renderer.destroy();
        cache.clear();
        pendingDiagrams.clear();
    }

    private void setImageSpanWithClick(Spannable spannable, Bitmap bitmap, int start, int end) {
        // Scale drawable bounds to density-independent size
        float density = context.getResources().getDisplayMetrics().density;
        int drawW = Math.round(bitmap.getWidth() / density);
        int drawH = Math.round(bitmap.getHeight() / density);

        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setBounds(0, 0, drawW, drawH);
        spannable.setSpan(
                new ImageSpan(drawable),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        // Add a ClickableSpan on the same range for tap-to-zoom
        spannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                if (diagramClickListener != null) {
                    diagramClickListener.onDiagramClick(bitmap);
                }
            }

            @Override
            public void updateDrawState(@NonNull android.text.TextPaint ds) {
                // Don't change text appearance
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void replacePlaceholder(TextView textView, ImageSpan placeholder, Bitmap bitmap) {
        try {
            CharSequence text = textView.getText();
            if (!(text instanceof Spannable)) return;

            Spannable spannable = (Spannable) text;
            int start = spannable.getSpanStart(placeholder);
            int end = spannable.getSpanEnd(placeholder);
            if (start < 0 || end < 0) return;

            spannable.removeSpan(placeholder);
            setImageSpanWithClick(spannable, bitmap, start, end);

            // Force re-layout so the line height accommodates the new image size
            textView.post(() -> textView.setText(textView.getText(), TextView.BufferType.SPANNABLE));
        } catch (Exception e) {
            android.util.Log.e("ZellijConnect", "Error replacing mermaid placeholder", e);
        }
    }

    private void replaceWithError(TextView textView, ImageSpan placeholder, String error) {
        try {
            CharSequence text = textView.getText();
            if (!(text instanceof Spannable)) return;

            Spannable spannable = (Spannable) text;
            int start = spannable.getSpanStart(placeholder);
            int end = spannable.getSpanEnd(placeholder);
            if (start < 0 || end < 0) return;

            spannable.removeSpan(placeholder);

            String errorText = "[Diagram error: " + error + "]";
            SpannableStringBuilder ssb = new SpannableStringBuilder(spannable);
            ssb.replace(start, end, errorText);
            ssb.setSpan(
                    new ForegroundColorSpan(Color.parseColor("#CF6679")),
                    start,
                    start + errorText.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            ssb.setSpan(
                    new StyleSpan(Typeface.ITALIC),
                    start,
                    start + errorText.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            textView.post(() -> textView.setText(ssb));
        } catch (Exception e) {
            android.util.Log.e("ZellijConnect", "Error replacing mermaid error placeholder", e);
        }
    }

    private Drawable createPlaceholderDrawable() {
        int width = 400;
        int height = 100;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#2d2d2d"));
        canvas.drawRect(0, 0, width, height, bgPaint);

        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#555555"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);
        canvas.drawRect(1, 1, width - 1, height - 1, borderPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#888888"));
        textPaint.setTextSize(28);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = (height - fm.ascent - fm.descent) / 2;
        canvas.drawText("Rendering diagram\u2026", width / 2f, textY, textPaint);

        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setBounds(0, 0, width, height);
        return drawable;
    }
}
