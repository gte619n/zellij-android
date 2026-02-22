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
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.commonmark.node.FencedCodeBlock;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.core.CorePlugin;

/**
 * Markwon plugin that intercepts ```mermaid fenced code blocks and renders
 * them as inline diagram images using MermaidRenderer (WebView + mermaid.js).
 *
 * Uses a two-pass approach:
 * Pass 1 (synchronous, during configureVisitor): Emits placeholder ImageSpans for
 *   mermaid blocks, or cached bitmaps if available. Non-mermaid blocks are delegated
 *   to the default code block rendering.
 * Pass 2 (async, after setMarkdown): renderPendingDiagrams() triggers WebView
 *   rendering and swaps placeholders with actual diagram bitmaps.
 */
public class MermaidPlugin extends AbstractMarkwonPlugin {

    private final MermaidRenderer renderer;
    private final MermaidBitmapCache cache;
    private final Context context;

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

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        builder.on(FencedCodeBlock.class, (visitor, node) -> {
            String info = node.getInfo() != null ? node.getInfo().trim() : "";

            if ("mermaid".equalsIgnoreCase(info)) {
                handleMermaidBlock(visitor, node);
            } else {
                // Delegate to default code block rendering
                // CorePlugin registers the default FencedCodeBlock visitor behavior
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
            BitmapDrawable drawable = new BitmapDrawable(context.getResources(), cached);
            drawable.setBounds(0, 0, cached.getWidth(), cached.getHeight());
            visitor.builder().setSpan(
                    new ImageSpan(drawable),
                    length,
                    length + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
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
        // Reproduce CorePlugin's default FencedCodeBlock rendering:
        // append the literal with code spans
        int length = visitor.length();
        visitor.visitChildren(node);

        String literal = node.getLiteral();
        if (literal != null) {
            // If visitChildren didn't add text (which is typical for FencedCodeBlock),
            // append the literal manually
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

        // Set max width from the TextView
        textView.post(() -> {
            int width = textView.getWidth() - textView.getPaddingLeft() - textView.getPaddingRight();
            if (width > 0) {
                renderer.setMaxWidth(width);
            }
        });

        List<PendingDiagram> toRender = new ArrayList<>(pendingDiagrams);
        pendingDiagrams.clear();

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

    private void replacePlaceholder(TextView textView, ImageSpan placeholder, Bitmap bitmap) {
        CharSequence text = textView.getText();
        if (!(text instanceof Spannable)) return;

        Spannable spannable = (Spannable) text;
        int start = spannable.getSpanStart(placeholder);
        int end = spannable.getSpanEnd(placeholder);
        if (start < 0 || end < 0) return;

        spannable.removeSpan(placeholder);

        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        spannable.setSpan(
                new ImageSpan(drawable),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        textView.invalidate();
    }

    private void replaceWithError(TextView textView, ImageSpan placeholder, String error) {
        CharSequence text = textView.getText();
        if (!(text instanceof Spannable)) return;

        Spannable spannable = (Spannable) text;
        int start = spannable.getSpanStart(placeholder);
        int end = spannable.getSpanEnd(placeholder);
        if (start < 0 || end < 0) return;

        spannable.removeSpan(placeholder);

        // Replace the placeholder character with error text
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
        textView.setText(ssb);
    }

    private Drawable createPlaceholderDrawable() {
        int width = 400;
        int height = 100;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Dark background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#2d2d2d"));
        canvas.drawRect(0, 0, width, height, bgPaint);

        // Border
        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.parseColor("#555555"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2);
        canvas.drawRect(1, 1, width - 1, height - 1, borderPaint);

        // Text
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
