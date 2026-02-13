package com.zellijconnect.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

public class ClipboardBridge {

    private static final String TAG = "ZellijConnect";

    private final Context context;

    public ClipboardBridge(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void setClipboard(String base64Data) {
        try {
            byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
            String text = new String(decoded, "UTF-8");
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Zellij", text);
                clipboard.setPrimaryClip(clip);
                Log.d(TAG, "Clipboard set from Zellij (" + text.length() + " chars)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set clipboard", e);
        }
    }

    @JavascriptInterface
    public String getClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        return Base64.encodeToString(text.toString().getBytes("UTF-8"), Base64.NO_WRAP);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get clipboard", e);
        }
        return "";
    }

    public static String getInjectionScript() {
        return "(" + CLIPBOARD_BRIDGE_JS + ")();";
    }

    private static final String CLIPBOARD_BRIDGE_JS =
        "function() {\n" +
        "  if (window.__zellijClipboardBridgeInstalled) return;\n" +
        "  window.__zellijClipboardBridgeInstalled = true;\n" +
        "\n" +
        "  // Override navigator.clipboard.writeText\n" +
        "  if (navigator.clipboard) {\n" +
        "    const origWriteText = navigator.clipboard.writeText;\n" +
        "    navigator.clipboard.writeText = function(text) {\n" +
        "      try {\n" +
        "        var b64 = btoa(unescape(encodeURIComponent(text)));\n" +
        "        ZellijClipboard.setClipboard(b64);\n" +
        "      } catch(e) { console.error('Clipboard bridge write error:', e); }\n" +
        "      return origWriteText ? origWriteText.call(navigator.clipboard, text) : Promise.resolve();\n" +
        "    };\n" +
        "\n" +
        "    const origReadText = navigator.clipboard.readText;\n" +
        "    navigator.clipboard.readText = function() {\n" +
        "      try {\n" +
        "        var b64 = ZellijClipboard.getClipboard();\n" +
        "        if (b64) {\n" +
        "          var text = decodeURIComponent(escape(atob(b64)));\n" +
        "          return Promise.resolve(text);\n" +
        "        }\n" +
        "      } catch(e) { console.error('Clipboard bridge read error:', e); }\n" +
        "      return origReadText ? origReadText.call(navigator.clipboard) : Promise.resolve('');\n" +
        "    };\n" +
        "  }\n" +
        "  console.log('ZellijConnect clipboard bridge installed');\n" +
        "}";
}
