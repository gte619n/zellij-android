package com.zellijconnect.app;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * JavaScript bridge that adds touch-based text selection with native Android
 * selection handles and clickable URL detection to the xterm.js terminal.
 *
 * On long-press, a transparent text overlay containing the terminal content
 * is placed over the terminal. Android's native WebView text selection
 * (with drag handles and action bar) operates on this overlay. When the
 * user copies or dismisses, the overlay is removed.
 */
public class TouchBridge {

    private static final String TAG = "ZellijConnect";

    private final Context context;
    private LinkOpenCallback linkOpenCallback;

    public interface LinkOpenCallback {
        void onOpenLink(String url);
    }

    public TouchBridge(Context context) {
        this.context = context;
    }

    public void setLinkOpenCallback(LinkOpenCallback callback) {
        this.linkOpenCallback = callback;
    }

    @JavascriptInterface
    public void openLink(String url) {
        Log.d(TAG, "TouchBridge: opening link " + url);
        if (linkOpenCallback != null) {
            new android.os.Handler(context.getMainLooper()).post(() -> {
                linkOpenCallback.onOpenLink(url);
            });
        }
    }

    @JavascriptInterface
    public void copyText(String text) {
        if (text == null || text.isEmpty()) return;
        Log.d(TAG, "TouchBridge: copying " + text.length() + " chars");
        try {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("Zellij", text);
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            Log.e(TAG, "TouchBridge: copy failed", e);
        }
    }

    @JavascriptInterface
    public void logDebug(String message) {
        Log.d(TAG, "TouchBridge JS: " + message);
    }

    public static String getInjectionScript() {
        return "(" + TOUCH_BRIDGE_JS + ")();";
    }

    private static final String TOUCH_BRIDGE_JS =
        "function() {\n" +
        "  if (window.__zellijTouchBridgeInstalled) return;\n" +
        "  window.__zellijTouchBridgeInstalled = true;\n" +
        "\n" +
        "  var LONG_PRESS_MS = 400;\n" +
        "  var MOVE_THRESHOLD = 10;\n" +
        "  var longPressTimer = null;\n" +
        "  var touchStartX = 0, touchStartY = 0;\n" +
        "  var overlay = null;\n" +
        "\n" +
        "  // ── helpers ──\n" +
        "  function getTerminal() {\n" +
        "    if (window.__zellijTerminal) return window.__zellijTerminal;\n" +
        "    if (window.term) return window.term;\n" +
        "    if (window.terminal) return window.terminal;\n" +
        "    var el = document.querySelector('.xterm');\n" +
        "    if (el && el._terminal) return el._terminal;\n" +
        "    return null;\n" +
        "  }\n" +
        "\n" +
        "  function getCellSize(term) {\n" +
        "    if (term._core && term._core._renderService) {\n" +
        "      var dims = term._core._renderService.dimensions;\n" +
        "      if (dims && dims.css && dims.css.cell) {\n" +
        "        return { width: dims.css.cell.width, height: dims.css.cell.height };\n" +
        "      }\n" +
        "    }\n" +
        "    var screen = document.querySelector('.xterm-screen');\n" +
        "    if (screen && term.cols && term.rows) {\n" +
        "      var rect = screen.getBoundingClientRect();\n" +
        "      return { width: rect.width / term.cols, height: rect.height / term.rows };\n" +
        "    }\n" +
        "    return { width: 9, height: 17 };\n" +
        "  }\n" +
        "\n" +
        "  function touchToCell(term, x, y) {\n" +
        "    var screen = document.querySelector('.xterm-screen');\n" +
        "    if (!screen) return null;\n" +
        "    var rect = screen.getBoundingClientRect();\n" +
        "    var cell = getCellSize(term);\n" +
        "    var col = Math.floor((x - rect.left) / cell.width);\n" +
        "    var row = Math.floor((y - rect.top) / cell.height);\n" +
        "    col = Math.max(0, Math.min(col, term.cols - 1));\n" +
        "    row = Math.max(0, Math.min(row, term.rows - 1));\n" +
        "    return { col: col, row: row };\n" +
        "  }\n" +
        "\n" +
        "  // ── extract visible terminal text ──\n" +
        "  function getVisibleText(term) {\n" +
        "    var buffer = term.buffer.active;\n" +
        "    var lines = [];\n" +
        "    for (var r = 0; r < term.rows; r++) {\n" +
        "      var line = buffer.getLine(r);\n" +
        "      if (!line) { lines.push(''); continue; }\n" +
        "      var text = '';\n" +
        "      for (var c = 0; c < term.cols; c++) {\n" +
        "        var cell = line.getCell(c);\n" +
        "        text += cell ? (cell.getChars() || ' ') : ' ';\n" +
        "      }\n" +
        "      lines.push(text.replace(/\\s+$/, ''));\n" +
        "    }\n" +
        "    return lines;\n" +
        "  }\n" +
        "\n" +
        "  // ── selection overlay using native Android handles ──\n" +
        "  function showSelectionOverlay(term, clientX, clientY) {\n" +
        "    removeOverlay();\n" +
        "\n" +
        "    var screen = document.querySelector('.xterm-screen');\n" +
        "    if (!screen) return;\n" +
        "    var rect = screen.getBoundingClientRect();\n" +
        "    var cell = getCellSize(term);\n" +
        "    var lines = getVisibleText(term);\n" +
        "\n" +
        "    // Inject selection styles\n" +
        "    if (!document.getElementById('__zc_sel_style')) {\n" +
        "      var style = document.createElement('style');\n" +
        "      style.id = '__zc_sel_style';\n" +
        "      style.textContent =\n" +
        "        '#__zc_sel_overlay { ' +\n" +
        "        '  color: transparent; ' +\n" +
        "        '  -webkit-text-fill-color: transparent; ' +\n" +
        "        '  caret-color: transparent; ' +\n" +
        "        '} ' +\n" +
        "        '#__zc_sel_overlay::selection { ' +\n" +
        "        '  background: rgba(100,149,237,0.45); ' +\n" +
        "        '  -webkit-text-fill-color: rgba(255,255,255,0.85); ' +\n" +
        "        '} ' +\n" +
        "        '#__zc_sel_overlay *::selection { ' +\n" +
        "        '  background: rgba(100,149,237,0.45); ' +\n" +
        "        '  -webkit-text-fill-color: rgba(255,255,255,0.85); ' +\n" +
        "        '} ' +\n" +
        "        '#__zc_dismiss_btn { ' +\n" +
        "        '  position: fixed; top: 8px; right: 8px; z-index: 10001; ' +\n" +
        "        '  padding: 5px 14px; border: none; border-radius: 14px; ' +\n" +
        "        '  background: rgba(255,255,255,0.92); color: #333; ' +\n" +
        "        '  font: 600 13px/1.3 sans-serif; cursor: pointer; ' +\n" +
        "        '  box-shadow: 0 1px 4px rgba(0,0,0,0.25); ' +\n" +
        "        '}';\n" +
        "      document.head.appendChild(style);\n" +
        "    }\n" +
        "\n" +
        "    // Create pre element with terminal text\n" +
        "    overlay = document.createElement('pre');\n" +
        "    overlay.id = '__zc_sel_overlay';\n" +
        "    overlay.style.cssText =\n" +
        "      'position:fixed;' +\n" +
        "      'left:' + rect.left + 'px;' +\n" +
        "      'top:' + rect.top + 'px;' +\n" +
        "      'width:' + rect.width + 'px;' +\n" +
        "      'height:' + rect.height + 'px;' +\n" +
        "      'margin:0;padding:0;border:0;overflow:hidden;' +\n" +
        "      'font-family:monospace;white-space:pre;' +\n" +
        "      'font-size:' + cell.height * 0.75 + 'px;' +\n" +
        "      'line-height:' + cell.height + 'px;' +\n" +
        "      'letter-spacing:' + Math.max(0, cell.width - cell.height * 0.45) * 0.5 + 'px;' +\n" +
        "      '-webkit-user-select:text;user-select:text;' +\n" +
        "      'z-index:9999;background:transparent;';\n" +
        "\n" +
        "    // Pad each line to full terminal width so selection geometry is correct\n" +
        "    var padded = lines.map(function(l) {\n" +
        "      while (l.length < term.cols) l += ' ';\n" +
        "      return l;\n" +
        "    });\n" +
        "    overlay.textContent = padded.join('\\n');\n" +
        "    document.body.appendChild(overlay);\n" +
        "\n" +
        "    // Dismiss button\n" +
        "    var btn = document.createElement('button');\n" +
        "    btn.id = '__zc_dismiss_btn';\n" +
        "    btn.textContent = 'Done';\n" +
        "    btn.addEventListener('click', function(e) {\n" +
        "      e.stopPropagation();\n" +
        "      autoCopyAndDismiss();\n" +
        "    });\n" +
        "    document.body.appendChild(btn);\n" +
        "\n" +
        "    // Programmatically select the word at the touch point\n" +
        "    setTimeout(function() {\n" +
        "      try {\n" +
        "        var range = document.caretRangeFromPoint(clientX, clientY);\n" +
        "        if (range) {\n" +
        "          var sel = window.getSelection();\n" +
        "          sel.removeAllRanges();\n" +
        "          sel.addRange(range);\n" +
        "          // Expand to word boundaries\n" +
        "          sel.modify('move', 'backward', 'word');\n" +
        "          sel.modify('extend', 'forward', 'word');\n" +
        "        }\n" +
        "      } catch(e) {\n" +
        "        console.error('TouchBridge: selection init error', e);\n" +
        "      }\n" +
        "    }, 50);\n" +
        "\n" +
        "    // Listen for copy event to auto-dismiss after copy\n" +
        "    document.addEventListener('copy', onCopyEvent, true);\n" +
        "\n" +
        "    if (typeof ZellijTouch !== 'undefined')\n" +
        "      ZellijTouch.logDebug('Selection overlay shown');\n" +
        "  }\n" +
        "\n" +
        "  function onCopyEvent(e) {\n" +
        "    // Browser copy happened — get the selected text and sync to Android clipboard\n" +
        "    var sel = window.getSelection();\n" +
        "    var text = sel ? sel.toString() : '';\n" +
        "    if (text && typeof ZellijTouch !== 'undefined') {\n" +
        "      ZellijTouch.copyText(text);\n" +
        "    }\n" +
        "    setTimeout(removeOverlay, 200);\n" +
        "  }\n" +
        "\n" +
        "  function autoCopyAndDismiss() {\n" +
        "    var sel = window.getSelection();\n" +
        "    var text = sel ? sel.toString() : '';\n" +
        "    if (text && text.trim()) {\n" +
        "      // Copy via bridge\n" +
        "      if (typeof ZellijTouch !== 'undefined') {\n" +
        "        ZellijTouch.copyText(text.trim());\n" +
        "      }\n" +
        "      // Also try navigator.clipboard\n" +
        "      try { navigator.clipboard.writeText(text.trim()); } catch(e) {}\n" +
        "      showBanner('Copied ' + text.trim().length + ' chars');\n" +
        "    }\n" +
        "    removeOverlay();\n" +
        "  }\n" +
        "\n" +
        "  function removeOverlay() {\n" +
        "    document.removeEventListener('copy', onCopyEvent, true);\n" +
        "    var el = document.getElementById('__zc_sel_overlay');\n" +
        "    if (el) el.remove();\n" +
        "    var btn = document.getElementById('__zc_dismiss_btn');\n" +
        "    if (btn) btn.remove();\n" +
        "    overlay = null;\n" +
        "    window.getSelection().removeAllRanges();\n" +
        "  }\n" +
        "\n" +
        "  // ── banner ──\n" +
        "  var selectionBanner = null;\n" +
        "  function showBanner(msg) {\n" +
        "    if (!selectionBanner) {\n" +
        "      selectionBanner = document.createElement('div');\n" +
        "      selectionBanner.style.cssText =\n" +
        "        'position:fixed;top:12px;left:50%;transform:translateX(-50%);' +\n" +
        "        'padding:6px 18px;border-radius:16px;' +\n" +
        "        'background:rgba(0,0,0,0.78);color:#fff;font:13px/1.4 sans-serif;' +\n" +
        "        'z-index:10002;pointer-events:none;opacity:0;transition:opacity .25s;';\n" +
        "      document.body.appendChild(selectionBanner);\n" +
        "    }\n" +
        "    selectionBanner.textContent = msg;\n" +
        "    selectionBanner.style.opacity = '1';\n" +
        "    clearTimeout(selectionBanner._t);\n" +
        "    selectionBanner._t = setTimeout(function() {\n" +
        "      selectionBanner.style.opacity = '0';\n" +
        "    }, 1500);\n" +
        "  }\n" +
        "\n" +
        "  // ── URL detection ──\n" +
        "  var URL_RE = /https?:\\/\\/[^\\s'\"<>\\]\\)}{]+/g;\n" +
        "\n" +
        "  function getUrlAtCell(term, col, row) {\n" +
        "    var buffer = term.buffer.active;\n" +
        "    var line = buffer.getLine(row);\n" +
        "    if (!line) return null;\n" +
        "    var text = '';\n" +
        "    for (var c = 0; c < term.cols; c++) {\n" +
        "      var cell = line.getCell(c);\n" +
        "      text += cell ? (cell.getChars() || ' ') : ' ';\n" +
        "    }\n" +
        "    var match;\n" +
        "    URL_RE.lastIndex = 0;\n" +
        "    while ((match = URL_RE.exec(text)) !== null) {\n" +
        "      var url = match[0].replace(/[.,;:!?)]+$/, '');\n" +
        "      var start = match.index;\n" +
        "      var end = start + url.length - 1;\n" +
        "      if (col >= start && col <= end) return url;\n" +
        "    }\n" +
        "    return null;\n" +
        "  }\n" +
        "\n" +
        "  // ── main touch handler on xterm-screen ──\n" +
        "  function install() {\n" +
        "    var screen = document.querySelector('.xterm-screen');\n" +
        "    if (!screen) {\n" +
        "      setTimeout(install, 500);\n" +
        "      return;\n" +
        "    }\n" +
        "\n" +
        "    screen.addEventListener('touchstart', function(e) {\n" +
        "      if (overlay) return; // already in selection mode\n" +
        "      if (e.touches.length !== 1) return;\n" +
        "      var touch = e.touches[0];\n" +
        "      touchStartX = touch.clientX;\n" +
        "      touchStartY = touch.clientY;\n" +
        "\n" +
        "      clearTimeout(longPressTimer);\n" +
        "      longPressTimer = setTimeout(function() {\n" +
        "        var term = getTerminal();\n" +
        "        if (!term) return;\n" +
        "        if (navigator.vibrate) navigator.vibrate(30);\n" +
        "        showSelectionOverlay(term, touchStartX, touchStartY);\n" +
        "      }, LONG_PRESS_MS);\n" +
        "    }, { passive: true });\n" +
        "\n" +
        "    screen.addEventListener('touchmove', function(e) {\n" +
        "      if (overlay) return;\n" +
        "      if (e.touches.length !== 1) return;\n" +
        "      var touch = e.touches[0];\n" +
        "      var dx = touch.clientX - touchStartX;\n" +
        "      var dy = touch.clientY - touchStartY;\n" +
        "      if (Math.sqrt(dx*dx + dy*dy) > MOVE_THRESHOLD) {\n" +
        "        clearTimeout(longPressTimer);\n" +
        "      }\n" +
        "    }, { passive: true });\n" +
        "\n" +
        "    screen.addEventListener('touchend', function(e) {\n" +
        "      clearTimeout(longPressTimer);\n" +
        "      if (overlay) return; // selection mode handles its own events\n" +
        "\n" +
        "      // Quick tap: check for URL\n" +
        "      if (e.changedTouches.length > 0) {\n" +
        "        var touch = e.changedTouches[0];\n" +
        "        var dx = touch.clientX - touchStartX;\n" +
        "        var dy = touch.clientY - touchStartY;\n" +
        "        if (Math.sqrt(dx*dx + dy*dy) < MOVE_THRESHOLD) {\n" +
        "          var term = getTerminal();\n" +
        "          if (term) {\n" +
        "            var pos = touchToCell(term, touch.clientX, touch.clientY);\n" +
        "            if (pos) {\n" +
        "              var url = getUrlAtCell(term, pos.col, pos.row);\n" +
        "              if (url && typeof ZellijTouch !== 'undefined') {\n" +
        "                ZellijTouch.openLink(url);\n" +
        "                e.preventDefault();\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    }, { passive: false });\n" +
        "\n" +
        "    screen.addEventListener('touchcancel', function() {\n" +
        "      clearTimeout(longPressTimer);\n" +
        "    }, { passive: true });\n" +
        "\n" +
        "    if (typeof ZellijTouch !== 'undefined')\n" +
        "      ZellijTouch.logDebug('Touch bridge installed (native selection mode)');\n" +
        "    console.log('ZellijConnect touch bridge installed');\n" +
        "  }\n" +
        "\n" +
        "  install();\n" +
        "}";
}
