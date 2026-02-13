# ZellijConnect - Android Application Specification

**Version:** 1.0.0-draft
**Created:** 2026-02-13
**Status:** IMPLEMENTATION COMPLETE - AWAITING DEVICE TESTING

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Interview Record](#2-interview-record)
3. [Technical Architecture](#3-technical-architecture)
4. [Development Environment](#4-development-environment)
5. [Implementation Phases](#5-implementation-phases)
6. [Phase Tracking Matrix](#6-phase-tracking-matrix)
7. [Risks & Mitigations](#7-risks--mitigations)
8. [Appendix](#8-appendix)

---

## 1. Project Overview

### 1.1 Problem Statement

Using Zellij's web client on Android (Samsung Fold 7) via Chrome has three key frustrations:

1. **Keyboard switching is manual** - Chrome cannot auto-switch to a terminal-compatible IME (Unexpected Keyboard), requiring tedious manual switching every time the user opens the app.
2. **Excessive browser chrome** - Chrome's address bar, navigation, bookmarks bar, and tab UI waste valuable screen real estate when only a single URL per tab is needed.
3. **Aggressive tab suspension** - Chrome aggressively kills background tab processes, severing WebSocket connections to Zellij and forcing full page reloads with "reconnecting..." delays.

### 1.2 Solution

**ZellijConnect** - A purpose-built, lightweight Android WebView application that:

- **Automatically switches** the system IME to Unexpected Keyboard on focus, and back to Gboard on blur
- **Removes all browser chrome**, presenting only a minimal tab strip and full-screen WebView
- **Keeps WebSocket connections alive** using wake locks and a foreground service, preventing Zellij disconnections
- **Targets the Samsung Galaxy Fold 7** inner display (7.6") running Android 15 (API 35)

### 1.3 Non-Goals (v1)

- No settings/configuration UI (URL is hardcoded, configurable later)
- No app launcher shortcuts
- No multi-window / DeX mode support
- No outer (cover) display optimization
- No landscape orientation mode
- No custom themes (Material You only)

---

## 2. Interview Record

All architectural decisions below were made through a 9-round structured interview with the user.

### 2.1 Connection & Server

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Connection method | Zellij native web client behind Tailscale | No SSH handling needed; pure WebView |
| Base URL | `https://mac-mini-m4.softshell-mark.ts.net:7600` | Hardcoded in `local.properties` (gitignored) |
| Gateway path | `/gateway` | '+' button opens this path to create/attach sessions |
| Authentication | None (Tailscale handles identity) | No cookie/auth/cert handling required |
| URL pattern | `https://host:port/path` | Reverse-proxied, paths distinguish sessions |

### 2.2 Keyboard & Input

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Terminal keyboard | Unexpected Keyboard (`juloo.keyboard2`) | User's preferred terminal IME; default layout, needs Ctrl/Esc |
| Default keyboard | Gboard (`com.google.android.inputmethod.latin`) | Switches back on app blur |
| Permission mechanism | ADB `WRITE_SECURE_SETTINGS` | Persists across reinstalls; one-time setup |
| Missing permission UX | One-time setup guide on first launch | Shows exact `adb` command; dismisses permanently |
| Keyboard resize behavior | `adjustResize` | Keyboard pushes WebView content up, not overlays |
| Hardware key remapping | Volume Up/Down = Page Up/Page Down | Intercepted at Activity level |

### 2.3 Tab Management

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Typical tab count | 4-7 | Scrollable horizontal tab strip |
| Tab bar position | Top | Below status bar (or at top edge in immersive mode) |
| Tab labels | Auto-extracted from URL path | e.g., `/session1` -> `session1` |
| Tab switching | Tap + horizontal swipe on tab bar | Swipe inside WebView goes to Zellij |
| New tab action | '+' opens gateway URL | `https://host:7600/gateway` |
| Tab close | Swipe tab away (vertical) | Minimum 1 tab; closing last tab opens gateway |
| Tab persistence | SharedPreferences across restarts | Restore all previous tabs on launch |
| Host topology | Single host, different paths | All tabs point to same Tailscale hostname |

### 2.4 WebView & Display

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Target display | Inner (unfolded) 7.6" only | No outer display optimization |
| Zoom | Disabled (fixed scale) | Terminal layout breaks with pinch-to-zoom |
| Immersive mode | Configurable toggle | Default: immersive; toggle via UI button |
| Theme | Material You / Dynamic Colors | Follows Samsung One UI system theme |
| Clipboard | OSC 52 bridge to Android clipboard | Seamless copy/paste between Zellij and Android |
| Touch interaction | Full touch/tap support in WebView | Needed for Zellij session picker and pane interaction |

### 2.5 Session Keep-Alive & Error Handling

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Keep-alive strategy | Partial wake locks + foreground service | Prevents Android from killing WebView processes |
| Foreground notification | Shows tab count + connection status | Useful info; tap returns to app |
| Resume behavior | Reload page on detected disconnect | Simple and reliable; Zellij preserves server-side state |
| Connection error UX | Inline banner with auto-retry | Non-intrusive; retries every few seconds |
| Background behavior | Allow disconnect, reconnect on resume | No persistent background connection needed |

### 2.6 Platform & Build

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java | User preference |
| Min/Target SDK | API 35 (Android 15) | Fold 7 only; no backward compat needed |
| Build tooling | Gradle CLI (no Android Studio) | Lightweight; closed-loop with `adb install` |
| Deployment | USB ADB | Device connected to Mac via USB cable |
| App name | ZellijConnect | Clear and descriptive |

---

## 3. Technical Architecture

### 3.1 Component Overview

```
+--------------------------------------------------+
|  ZellijConnect App                                |
|                                                   |
|  +--------------------------------------------+  |
|  |  TabStripView (RecyclerView, horizontal)    |  |
|  |  [session1] [gateway] [logs] [+]            |  |
|  +--------------------------------------------+  |
|  |                                              | |
|  |  WebViewContainer (ViewFlipper/FrameLayout)  | |
|  |  +----------------------------------------+  |
|  |  |  WebView (active tab)                  |  |
|  |  |  - JavaScript enabled                  |  |
|  |  |  - WebSocket support                   |  |
|  |  |  - OSC 52 clipboard bridge             |  |
|  |  |  - Fixed viewport scale                |  |
|  |  +----------------------------------------+  |
|  |                                              | |
|  +--------------------------------------------+  |
|                                                   |
|  Services:                                        |
|  - KeepAliveService (foreground, wake lock)       |
|  - IMESwitchManager (WRITE_SECURE_SETTINGS)       |
|                                                   |
+--------------------------------------------------+
```

### 3.2 Key Classes

| Class | Responsibility |
|-------|---------------|
| `MainActivity` | Entry point; manages window flags, immersive mode, volume key interception |
| `TabManager` | Tab CRUD, persistence (SharedPreferences), active tab tracking |
| `TabStripView` | Custom horizontal RecyclerView with swipe-to-dismiss and swipe-to-navigate |
| `WebViewPool` | Creates/caches WebView instances per tab; manages lifecycle |
| `IMESwitchManager` | Switches IME on focus/blur using `Settings.Secure` API |
| `KeepAliveService` | Foreground service holding partial wake lock; manages notification |
| `ConnectionMonitor` | Detects WebView errors; shows inline retry banner |
| `ClipboardBridge` | JavaScript interface for OSC 52 clipboard sync |
| `SetupGuideActivity` | One-time ADB permission setup instructions |
| `AppConfig` | Reads hardcoded/BuildConfig values for base URL, gateway path, IME IDs |

### 3.3 IME Switching Mechanism

```
App gains focus (onWindowFocusChanged=true):
  1. Read current IME via Settings.Secure.getString(DEFAULT_INPUT_METHOD)
  2. If current != Unexpected Keyboard:
     Settings.Secure.putString(DEFAULT_INPUT_METHOD, "juloo.keyboard2/.Keyboard2")
  3. Force show keyboard via InputMethodManager.showSoftInput()

App loses focus (onWindowFocusChanged=false):
  1. Settings.Secure.putString(DEFAULT_INPUT_METHOD,
     "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
```

**Required ADB command (one-time):**
```bash
adb shell pm grant com.zellijconnect.app android.permission.WRITE_SECURE_SETTINGS
```

### 3.4 WebView Configuration

```java
WebSettings settings = webView.getSettings();
settings.setJavaScriptEnabled(true);
settings.setDomStorageEnabled(true);
settings.setDatabaseEnabled(true);
settings.setMediaPlaybackRequiresUserGesture(false);
settings.setCacheMode(WebSettings.LOAD_DEFAULT);
settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
settings.setSupportZoom(false);
settings.setBuiltInZoomControls(false);
settings.setDisplayZoomControls(false);
settings.setUseWideViewPort(true);
settings.setLoadWithOverviewMode(true);

// Prevent text selection conflicts
webView.setLongClickable(false);
webView.setHapticFeedbackEnabled(false);
```

### 3.5 Keep-Alive Strategy

```
KeepAliveService (START_STICKY):
  1. Acquire PARTIAL_WAKE_LOCK (CPU stays awake)
  2. Post persistent notification (FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
  3. Update notification: "{N} active tabs - Connected"
  4. On app background: keep service alive
  5. On app destroy: release wake lock, stop service
```

### 3.6 Tab Persistence Schema

```json
// SharedPreferences: "zellij_tabs"
{
  "tabs": [
    {"id": "uuid-1", "url": "https://host:7600/session1", "label": "session1", "position": 0},
    {"id": "uuid-2", "url": "https://host:7600/gateway", "label": "gateway", "position": 1}
  ],
  "activeTabId": "uuid-1"
}
```

### 3.7 OSC 52 Clipboard Bridge

Zellij's terminal emulator uses OSC 52 escape sequences for clipboard operations. The bridge:

1. Inject JavaScript `WebViewClient.onPageFinished()` that listens for clipboard events
2. Expose `@JavascriptInterface` method `setClipboard(String base64Data)`
3. Expose `@JavascriptInterface` method `getClipboard()` returning base64
4. Android side: read/write `ClipboardManager` system service

### 3.8 Connection Error Handling

```
WebViewClient.onReceivedError() / onReceivedHttpError():
  1. Overlay semi-transparent banner on WebView: "Connection lost. Retrying..."
  2. Schedule retry: 2s, 4s, 8s, 16s, 30s (exponential backoff, max 30s)
  3. On success: dismiss banner
  4. Banner has manual "Retry Now" button

Activity.onResume():
  1. For each tab's WebView, check if page is in error state
  2. If error or Zellij shows "reconnecting": webView.reload()
```

---

## 4. Development Environment

### 4.1 Current State

| Component | Status | Details |
|-----------|--------|---------|
| Java | INSTALLED | OpenJDK 21.0.3 via SDKMAN |
| Gradle | INSTALLED | Via Homebrew |
| Android SDK | NOT INSTALLED | No `ANDROID_HOME`, no `adb`, no `sdkmanager` |
| Android Build Tools | NOT INSTALLED | Required for compilation |
| Platform Tools | NOT INSTALLED | Required for `adb` |
| Android API 35 | NOT INSTALLED | Target platform |

### 4.2 Required Installation

```bash
# 1. Install Android command-line tools via Homebrew
brew install --cask android-commandlinetools

# 2. Set environment variables (~/.zshrc or ~/.bashrc)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 3. Accept licenses
sdkmanager --licenses

# 4. Install required SDK components
sdkmanager "platforms;android-35"
sdkmanager "build-tools;35.0.0"
sdkmanager "platform-tools"

# 5. Verify
adb version
```

### 4.3 Build & Deploy Loop

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch app
adb shell am start -n com.zellijconnect.app/.MainActivity

# View logs (filtered to our app)
adb logcat -s ZellijConnect:V

# One-time: grant IME switching permission
adb shell pm grant com.zellijconnect.app android.permission.WRITE_SECURE_SETTINGS

# Shortcut: build + install + launch
./gradlew installDebug && adb shell am start -n com.zellijconnect.app/.MainActivity
```

### 4.4 Project Structure

```
zellij-android/
  app/
    src/main/
      java/com/zellijconnect/app/
        MainActivity.java         # Main activity, wires all components
        TabManager.java           # Tab state CRUD and persistence
        TabAdapter.java           # RecyclerView adapter for tab strip
        WebViewPool.java          # WebView lifecycle per tab
        IMESwitchManager.java     # IME switching via WRITE_SECURE_SETTINGS
        KeepAliveService.java     # Foreground service with wake lock
        ConnectionMonitor.java    # Error detection and retry logic
        ClipboardBridge.java      # OSC 52 clipboard JS bridge
        SetupGuideActivity.java   # One-time ADB permission setup
        AppConfig.java            # BuildConfig accessor
      res/
        layout/
          activity_main.xml       # Tab strip + WebView container + error banner
          tab_item.xml            # Individual tab in strip
          activity_setup_guide.xml # Setup instructions
        values/
          strings.xml
          themes.xml              # Material You theme
          colors.xml
        drawable/
          ic_add_tab.xml
          ic_fullscreen.xml
          ic_launcher_foreground.xml
          ic_launcher_background.xml
          ic_notification.xml
        mipmap-anydpi-v26/
          ic_launcher.xml         # Adaptive icon
        xml/
          network_security_config.xml  # Trust system + user certs
      AndroidManifest.xml
    build.gradle
    proguard-rules.pro
  build.gradle (root)
  settings.gradle
  gradle.properties
  local.properties          # gitignored; contains ZELLIJ_BASE_URL
  gradlew / gradlew.bat    # Gradle wrapper (8.11.1)
  docs/
    plans/
      SPEC.md               # this file
  .gitignore
```

### 4.5 local.properties (gitignored)

```properties
# Server configuration (not committed to git)
ZELLIJ_BASE_URL=https://mac-mini-m4.softshell-mark.ts.net:7600
ZELLIJ_GATEWAY_PATH=/gateway

# IME package IDs (verify with: adb shell ime list -s)
TERMINAL_IME_ID=juloo.keyboard2/.Keyboard2
DEFAULT_IME_ID=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

---

## 5. Implementation Phases

### Phase 1: Scaffolding & Hello World (Foundation)

**Goal:** Buildable Android project that deploys to the Fold 7 via CLI.

**Tasks:**
1. Install Android SDK, platform tools, build tools (API 35)
2. Set up environment variables (`ANDROID_HOME`, `PATH`)
3. Initialize Gradle project with `app` module
4. Create `AndroidManifest.xml` with basic activity
5. Create `MainActivity.java` with a single WebView loading a test URL
6. Create `build.gradle` files (root + app) targeting API 35
7. Create `.gitignore` covering Android artifacts
8. Build, install, and verify on device: `./gradlew installDebug`
9. Verify WebView loads a page successfully

**Exit Criteria:** APK installs on Fold 7, shows a WebView with a test page.

---

### Phase 2: WebView Core & Zellij Connection

**Goal:** Connect to the actual Zellij server with proper WebView configuration.

**Tasks:**
1. Create `AppConfig.java` reading from `BuildConfig` fields (sourced from `local.properties`)
2. Configure `build.gradle` to inject `local.properties` values into `BuildConfig`
3. Create `local.properties` with actual server URL, gateway path, IME IDs
4. Configure WebView settings (JS enabled, no zoom, DOM storage, WebSocket support)
5. Load `ZELLIJ_BASE_URL + ZELLIJ_GATEWAY_PATH` in WebView
6. Handle SSL/TLS for Tailscale (trust system certificates)
7. Test: Zellij web client loads and is interactive via touch
8. Test: Can type in terminal via default keyboard

**Exit Criteria:** Zellij terminal is fully functional in the app's WebView.

---

### Phase 3: IME Auto-Switching

**Goal:** Automatically switch to Unexpected Keyboard on focus, back to Gboard on blur.

**Tasks:**
1. Create `IMESwitchManager.java` encapsulating IME switch logic
2. Implement `WRITE_SECURE_SETTINGS` check on startup
3. Create `SetupGuideActivity.java` with ADB command instructions
4. Wire `onWindowFocusChanged()` in `MainActivity` to `IMESwitchManager`
5. Implement focus-gained: switch to terminal IME + show keyboard
6. Implement focus-lost: switch to default IME
7. Add `adjustResize` to `AndroidManifest.xml` for keyboard push behavior
8. Test: Grant permission via `adb shell pm grant`
9. Test: Open app -> keyboard switches to Unexpected Keyboard
10. Test: Switch to another app -> keyboard switches back to Gboard
11. Test: Return to app -> keyboard switches back to Unexpected Keyboard

**Exit Criteria:** Keyboard auto-switches reliably on every focus change.

---

### Phase 4: Tab Management

**Goal:** Multiple tabs with a horizontal tab strip, add/close/switch functionality.

**Tasks:**
1. Create `TabManager.java` for tab state management
2. Create `TabStripView.java` (horizontal RecyclerView with ItemTouchHelper)
3. Create `tab_item.xml` layout (label + active indicator)
4. Create `WebViewPool.java` to manage WebView instances per tab
5. Implement `activity_main.xml` with tab strip + WebView container
6. Implement tab creation: '+' button loads gateway URL
7. Implement tab switching: tap to select, WebView swaps
8. Implement horizontal swipe gesture on tab strip to switch tabs
9. Implement swipe-to-dismiss (vertical) on tab items to close
10. Implement minimum-1-tab rule (closing last tab opens gateway)
11. Auto-extract tab label from URL path segment
12. Implement tab persistence to SharedPreferences
13. Implement tab restoration on app startup
14. Apply Material You / Dynamic Colors theming to tab strip
15. Test: Create multiple tabs, switch between them
16. Test: Close tabs via swipe, verify minimum-1 behavior
17. Test: Kill app, reopen, verify tabs restore

**Exit Criteria:** Full tab management works smoothly with persistence.

---

### Phase 5: Keep-Alive Service & Connection Resilience

**Goal:** Prevent WebSocket disconnections; handle errors gracefully.

**Tasks:**
1. Create `KeepAliveService.java` as a foreground service
2. Implement `PARTIAL_WAKE_LOCK` acquisition
3. Create notification channel and persistent notification
4. Update notification with tab count and connection status
5. Start service when app has active tabs; stop when no tabs
6. Create `ConnectionMonitor.java` for WebView error detection
7. Create `error_banner.xml` overlay layout
8. Implement `WebViewClient.onReceivedError()` handling
9. Implement exponential backoff retry (2s, 4s, 8s, 16s, max 30s)
10. Implement `onResume()` reconnection: detect stale pages, reload
11. Declare foreground service permissions in manifest
12. Test: Leave app for 30 minutes, return -> still connected
13. Test: Disable Tailscale -> banner appears with retry
14. Test: Re-enable Tailscale -> auto-reconnects

**Exit Criteria:** WebSocket stays alive for extended periods; graceful error recovery.

---

### Phase 6: Volume Key Remapping & Immersive Mode

**Goal:** Volume keys send Page Up/Down; configurable full-screen mode.

**Tasks:**
1. Override `dispatchKeyEvent()` in `MainActivity` for volume keys
2. Inject JavaScript to send Page Up/Down key events to WebView
3. Implement immersive mode toggle (hide status bar + nav bar)
4. Add toggle button in tab strip area (small icon)
5. Implement `WindowInsetsController` for immersive mode
6. Persist immersive mode preference in SharedPreferences
7. Test: Volume up scrolls terminal up (Page Up)
8. Test: Volume down scrolls terminal down (Page Down)
9. Test: Toggle immersive mode on/off

**Exit Criteria:** Volume keys work as Page Up/Down; immersive mode toggles cleanly.

---

### Phase 7: Clipboard Bridge (OSC 52)

**Goal:** Seamless clipboard sync between Zellij terminal and Android.

**Tasks:**
1. Create `ClipboardBridge.java` with `@JavascriptInterface` methods
2. Implement `setClipboard(String base64)` -> decode -> `ClipboardManager.setPrimaryClip()`
3. Implement `getClipboard()` -> `ClipboardManager.getPrimaryClip()` -> encode base64
4. Create JavaScript injection snippet for OSC 52 interception
5. Inject via `WebViewClient.onPageFinished()`
6. Register JavaScript interface on each WebView: `addJavascriptInterface(bridge, "ZellijClipboard")`
7. Test: Copy text in Zellij -> paste in another Android app
8. Test: Copy text in Android app -> paste in Zellij terminal

**Exit Criteria:** Bidirectional clipboard works between Zellij and Android.

---

### Phase 8: Polish & Optimization

**Goal:** Production-quality UX polish and edge case handling.

**Tasks:**
1. Create app icon (terminal/tile mosaic motif) as adaptive icon
2. Implement proper back button behavior (close tab or minimize)
3. Handle configuration changes (fold/unfold, rotation) without losing state
4. Add splash screen / loading state while WebView initializes
5. Optimize WebView memory usage for 7 concurrent instances
6. Handle Unexpected Keyboard not installed (graceful degradation)
7. Handle `local.properties` missing values (clear error message)
8. Add ProGuard/R8 rules for release build
9. Test full end-to-end workflow: launch -> setup guide -> use terminal -> multiple tabs -> background -> resume
10. Performance testing: memory usage with 7 active WebViews

**Exit Criteria:** App feels polished and handles all edge cases gracefully.

---

## 6. Phase Tracking Matrix

> Update this table as implementation progresses. Each task gets a status marker.

### Legend

| Symbol | Meaning |
|--------|---------|
| ` ` (blank) | Not started |
| `~` | In progress |
| `x` | Complete |
| `T` | Tested on device |
| `P` | Pushed to repo |

### Phase 1: Scaffolding & Hello World

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 1.1 | Install Android SDK | x | x | |
| 1.2 | Set up env vars | x | x | |
| 1.3 | Init Gradle project | x | x | |
| 1.4 | AndroidManifest.xml | x | | |
| 1.5 | Basic MainActivity + WebView | x | | |
| 1.6 | build.gradle files | x | x | |
| 1.7 | .gitignore | x | | |
| 1.8 | Build + install on device | x | | |
| 1.9 | Verify WebView loads | | | |

### Phase 2: WebView Core & Zellij Connection

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 2.1 | AppConfig.java | x | | |
| 2.2 | BuildConfig from local.properties | x | x | |
| 2.3 | local.properties with real URL | x | | |
| 2.4 | WebView settings (JS, no zoom, etc.) | x | | |
| 2.5 | Load Zellij URL | x | | |
| 2.6 | SSL/TLS handling | x | | |
| 2.7 | Test: Zellij interactive via touch | | | |
| 2.8 | Test: Typing works | | | |

### Phase 3: IME Auto-Switching

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 3.1 | IMESwitchManager.java | x | | |
| 3.2 | WRITE_SECURE_SETTINGS check | x | | |
| 3.3 | SetupGuideActivity.java | x | | |
| 3.4 | Wire onWindowFocusChanged | x | | |
| 3.5 | Focus-gained: switch to terminal IME | x | | |
| 3.6 | Focus-lost: switch to default IME | x | | |
| 3.7 | adjustResize in manifest | x | | |
| 3.8 | Grant ADB permission | | | |
| 3.9 | Test: auto-switch on open | | | |
| 3.10 | Test: switch back on blur | | | |
| 3.11 | Test: switch on return | | | |

### Phase 4: Tab Management

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 4.1 | TabManager.java | x | | |
| 4.2 | TabStripView (TabAdapter.java) | x | | |
| 4.3 | tab_item.xml | x | | |
| 4.4 | WebViewPool.java | x | | |
| 4.5 | activity_main.xml layout | x | | |
| 4.6 | '+' button -> gateway | x | | |
| 4.7 | Tab switching (tap) | x | | |
| 4.8 | Tab strip swipe gesture | x | | |
| 4.9 | Swipe-to-dismiss close | x | | |
| 4.10 | Minimum-1-tab rule | x | | |
| 4.11 | Auto-extract tab labels | x | | |
| 4.12 | Tab persistence (save) | x | | |
| 4.13 | Tab restoration (load) | x | | |
| 4.14 | Material You theming | x | | |
| 4.15 | Test: multi-tab workflow | | | |
| 4.16 | Test: swipe close + min-1 | | | |
| 4.17 | Test: kill + restore | | | |

### Phase 5: Keep-Alive & Connection Resilience

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 5.1 | KeepAliveService.java | x | | |
| 5.2 | PARTIAL_WAKE_LOCK | x | | |
| 5.3 | Notification channel + notification | x | | |
| 5.4 | Notification content updates | x | | |
| 5.5 | Service lifecycle management | x | | |
| 5.6 | ConnectionMonitor.java | x | | |
| 5.7 | error_banner.xml (inline in activity_main) | x | | |
| 5.8 | WebView error handling | x | | |
| 5.9 | Exponential backoff retry | x | | |
| 5.10 | onResume reconnection | x | | |
| 5.11 | Manifest permissions | x | | |
| 5.12 | Test: 30-min background | | | |
| 5.13 | Test: Tailscale disconnect | | | |
| 5.14 | Test: auto-reconnect | | | |

### Phase 6: Volume Keys & Immersive Mode

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 6.1 | Volume key interception | x | | |
| 6.2 | JS Page Up/Down injection | x | | |
| 6.3 | Immersive mode toggle | x | | |
| 6.4 | Toggle button UI | x | | |
| 6.5 | WindowInsetsController | x | | |
| 6.6 | Persist immersive preference | x | | |
| 6.7 | Test: volume keys | | | |
| 6.8 | Test: immersive toggle | | | |

### Phase 7: Clipboard Bridge

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 7.1 | ClipboardBridge.java | x | | |
| 7.2 | setClipboard implementation | x | | |
| 7.3 | getClipboard implementation | x | | |
| 7.4 | OSC 52 JS injection snippet | x | | |
| 7.5 | onPageFinished injection | x | | |
| 7.6 | Register JS interface per WebView | x | | |
| 7.7 | Test: Zellij -> Android clipboard | | | |
| 7.8 | Test: Android -> Zellij clipboard | | | |

### Phase 8: Polish & Optimization

| # | Task | Impl | Test | Push |
|---|------|------|------|------|
| 8.1 | App icon (adaptive) | x | | |
| 8.2 | Back button behavior | x | | |
| 8.3 | Configuration change handling | x | | |
| 8.4 | Splash / loading state | | | |
| 8.5 | WebView memory optimization | | | |
| 8.6 | Unexpected KB not installed handling | | | |
| 8.7 | Missing local.properties handling | | | |
| 8.8 | ProGuard/R8 rules | x | | |
| 8.9 | End-to-end workflow test | | | |
| 8.10 | Memory performance test (7 tabs) | | | |

---

## 7. Risks & Mitigations

### 7.1 Technical Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| WebView WebSocket drops despite wake lock | HIGH | MEDIUM | Add JS-based heartbeat as fallback; monitor via ConnectionMonitor |
| Unexpected Keyboard IME ID varies by version/source | MEDIUM | LOW | Verify on device with `adb shell ime list -s`; make configurable in local.properties |
| WRITE_SECURE_SETTINGS denied on Samsung One UI | HIGH | LOW | Samsung supports this; tested on S-series. Fallback: manual switching |
| 7 WebViews cause OOM on Fold 7 | MEDIUM | LOW | Fold 7 has 12GB+ RAM; implement WebView recycling if needed |
| OSC 52 not supported by Zellij web client | MEDIUM | MEDIUM | Test early in Phase 7; fallback to standard web clipboard API |
| Android 15 changes foreground service restrictions | HIGH | MEDIUM | Use `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` with proper declaration |
| Tailscale certificate not in system trust store | MEDIUM | LOW | Tailscale uses system-level VPN; WebView trusts system certs |

### 7.2 UX Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Tab swipe conflicts with Zellij gestures | MEDIUM | Tab swipe restricted to tab bar area only |
| Keyboard push-up leaves too few terminal rows | LOW | User confirmed "whatever remains is fine"; Zellij auto-adapts |
| Immersive mode makes it hard to access system | LOW | Swipe from edge reveals system bars temporarily |

---

## 8. Appendix

### 8.1 Useful ADB Commands

```bash
# List installed IMEs
adb shell ime list -s

# Get current IME
adb shell settings get secure default_input_method

# Set IME manually
adb shell settings put secure default_input_method juloo.keyboard2/.Keyboard2

# Grant WRITE_SECURE_SETTINGS
adb shell pm grant com.zellijconnect.app android.permission.WRITE_SECURE_SETTINGS

# Check if permission is granted
adb shell dumpsys package com.zellijconnect.app | grep WRITE_SECURE_SETTINGS

# View app logs
adb logcat -s ZellijConnect:V

# Force stop app
adb shell am force-stop com.zellijconnect.app

# Screen capture (for debugging)
adb exec-out screencap -p > screen.png
```

### 8.2 Samsung Fold 7 Inner Display Specs

| Property | Value |
|----------|-------|
| Size | 7.6" |
| Resolution | 2176 x 1812 pixels |
| Density | ~373 dpi (xxhdpi) |
| Aspect Ratio | ~6:5 (nearly square when unfolded) |
| Android Version | 15 (API 35) |
| One UI Version | 7.x |

### 8.3 Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Android Gradle Plugin | 8.7.x | Build system |
| Material Components | 1.12.x | Material You / Dynamic Colors |
| AndroidX Core | 1.15.x | Backward compat utilities |
| AndroidX Activity | 1.10.x | Activity lifecycle |
| RecyclerView | 1.4.x | Tab strip implementation |

### 8.4 Manifest Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Note: `WRITE_SECURE_SETTINGS` is NOT declared in manifest. It is granted via `adb shell pm grant` at runtime as a special permission.
