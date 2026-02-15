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
        "\n" +
        "  // OSC 52 clipboard handler for xterm.js\n" +
        "  function handleOsc52(data) {\n" +
        "    // OSC 52 format: Pc;Pd where Pc is clipboard target, Pd is base64 data or '?'\n" +
        "    var parts = data.split(';');\n" +
        "    if (parts.length < 2) return false;\n" +
        "    var target = parts[0]; // 'c' for clipboard, 'p' for primary, etc.\n" +
        "    var payload = parts.slice(1).join(';'); // rejoin in case data contains ';'\n" +
        "    \n" +
        "    if (payload === '?') {\n" +
        "      // Query clipboard - respond with OSC 52 containing clipboard data\n" +
        "      try {\n" +
        "        var b64 = ZellijClipboard.getClipboard();\n" +
        "        if (b64 && window.__zellijTerminal) {\n" +
        "          // Send response: OSC 52 ; target ; base64data ST\n" +
        "          var response = '\\x1b]52;' + target + ';' + b64 + '\\x1b\\\\\\\\';\n" +
        "          // This would need to be sent back through the terminal's input\n" +
        "          console.log('OSC 52 query - clipboard has', b64.length, 'bytes');\n" +
        "        }\n" +
        "      } catch(e) { console.error('OSC 52 query error:', e); }\n" +
        "    } else if (payload && payload.length > 0) {\n" +
        "      // Set clipboard\n" +
        "      try {\n" +
        "        ZellijClipboard.setClipboard(payload);\n" +
        "        console.log('OSC 52 set clipboard:', payload.length, 'bytes base64');\n" +
        "      } catch(e) { console.error('OSC 52 set error:', e); }\n" +
        "    }\n" +
        "    return true;\n" +
        "  }\n" +
        "\n" +
        "  // Hook into xterm.js terminal if available\n" +
        "  function hookXterm(term) {\n" +
        "    if (!term || !term.parser || window.__osc52Hooked) return;\n" +
        "    window.__osc52Hooked = true;\n" +
        "    window.__zellijTerminal = term;\n" +
        "    \n" +
        "    // Register OSC 52 handler\n" +
        "    term.parser.registerOscHandler(52, function(data) {\n" +
        "      return handleOsc52(data);\n" +
        "    });\n" +
        "    console.log('OSC 52 handler registered via xterm parser');\n" +
        "  }\n" +
        "\n" +
        "  // Find xterm terminal instance\n" +
        "  function findTerminal() {\n" +
        "    // Check common locations where xterm.js terminal might be stored\n" +
        "    if (window.term) return window.term;\n" +
        "    if (window.terminal) return window.terminal;\n" +
        "    if (window.Terminal && window.Terminal._core) return window.Terminal;\n" +
        "    \n" +
        "    // Search for xterm instance in DOM\n" +
        "    var xtermEl = document.querySelector('.xterm');\n" +
        "    if (xtermEl && xtermEl._terminal) return xtermEl._terminal;\n" +
        "    \n" +
        "    // Check for Zellij-specific locations\n" +
        "    if (window.__ZELLIJ__ && window.__ZELLIJ__.terminal) return window.__ZELLIJ__.terminal;\n" +
        "    \n" +
        "    return null;\n" +
        "  }\n" +
        "\n" +
        "  // Fallback: intercept WebSocket messages for OSC 52\n" +
        "  function hookWebSocket() {\n" +
        "    if (window.__wsHooked) return;\n" +
        "    window.__wsHooked = true;\n" +
        "    \n" +
        "    var OrigWebSocket = window.WebSocket;\n" +
        "    window.WebSocket = function(url, protocols) {\n" +
        "      var ws = protocols ? new OrigWebSocket(url, protocols) : new OrigWebSocket(url);\n" +
        "      \n" +
        "      // Use addEventListener instead of overriding onmessage\n" +
        "      // This avoids breaking the WebSocket's internal message handling\n" +
        "      ws.addEventListener('message', function(event) {\n" +
        "        try {\n" +
        "          if (event.data && typeof event.data === 'string') {\n" +
        "            checkForOsc52(event.data);\n" +
        "          } else if (event.data instanceof ArrayBuffer) {\n" +
        "            var text = new TextDecoder().decode(event.data);\n" +
        "            checkForOsc52(text);\n" +
        "          } else if (event.data instanceof Blob) {\n" +
        "            event.data.text().then(function(text) {\n" +
        "              checkForOsc52(text);\n" +
        "            }).catch(function() {});\n" +
        "          }\n" +
        "        } catch(e) {\n" +
        "          console.error('OSC 52 check error:', e);\n" +
        "        }\n" +
        "      });\n" +
        "      \n" +
        "      return ws;\n" +
        "    };\n" +
        "    window.WebSocket.prototype = OrigWebSocket.prototype;\n" +
        "    Object.keys(OrigWebSocket).forEach(function(key) {\n" +
        "      try { window.WebSocket[key] = OrigWebSocket[key]; } catch(e) {}\n" +
        "    });\n" +
        "    console.log('WebSocket hooked for OSC 52 detection');\n" +
        "  }\n" +
        "  \n" +
        "  // Check string for OSC 52 sequences\n" +
        "  function checkForOsc52(data) {\n" +
        "    // OSC 52 pattern: ESC ] 52 ; <target> ; <base64> BEL/ST\n" +
        "    // ESC = \\x1b, BEL = \\x07, ST = ESC \\\\\n" +
        "    var osc52Regex = /\\x1b\\]52;([^;]*);([^\\x07\\x1b]*)(?:\\x07|\\x1b\\\\)/g;\n" +
        "    var match;\n" +
        "    while ((match = osc52Regex.exec(data)) !== null) {\n" +
        "      var target = match[1];\n" +
        "      var payload = match[2];\n" +
        "      if (payload && payload !== '?') {\n" +
        "        console.log('OSC 52 detected via WebSocket:', target, payload.length, 'bytes');\n" +
        "        handleOsc52(target + ';' + payload);\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "\n" +
        "  // Try xterm hook first, then WebSocket fallback\n" +
        "  hookWebSocket();\n" +
        "  \n" +
        "  var term = findTerminal();\n" +
        "  if (term) {\n" +
        "    hookXterm(term);\n" +
        "  } else {\n" +
        "    // Watch for terminal creation\n" +
        "    var observer = new MutationObserver(function(mutations) {\n" +
        "      var term = findTerminal();\n" +
        "      if (term) {\n" +
        "        hookXterm(term);\n" +
        "        observer.disconnect();\n" +
        "      }\n" +
        "    });\n" +
        "    observer.observe(document.body, { childList: true, subtree: true });\n" +
        "    \n" +
        "    // Also try periodically for a few seconds\n" +
        "    var attempts = 0;\n" +
        "    var interval = setInterval(function() {\n" +
        "      var term = findTerminal();\n" +
        "      if (term) {\n" +
        "        hookXterm(term);\n" +
        "        clearInterval(interval);\n" +
        "      } else if (++attempts > 20) {\n" +
        "        clearInterval(interval);\n" +
        "      }\n" +
        "    }, 500);\n" +
        "  }\n" +
        "\n" +
        "  console.log('ZellijConnect clipboard bridge installed');\n" +
        "}";
}
