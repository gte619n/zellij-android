# ZellijConnect

A native Android app for connecting to Zellij terminal sessions via the Zellij web client. Built specifically for the Samsung Galaxy Fold 7 running Android 15.

## Features

- **Multi-tab support** - Open multiple Zellij sessions in separate tabs
- **Automatic keyboard switching** - Switches to terminal keyboard (Unexpected Keyboard) when focused, Gboard when unfocused
- **Volume key scrolling** - Use volume buttons to scroll terminal history
- **Keep-alive service** - Maintains WebSocket connections in background
- **Immersive mode** - Hide system bars for maximum terminal space
- **External link handling** - HTTP links open in phone browser
- **Copy/paste support** - Long-press to select and copy text

## Setup

### 1. Configure `local.properties`

Create `local.properties` in the project root (this file is gitignored):

```properties
# Android SDK location
sdk.dir=/path/to/your/android/sdk

# Zellij server configuration
ZELLIJ_BASE_URL=https://your-server:7600
ZELLIJ_GATEWAY_PATH=/gateway

# Zellij authentication token
ZELLIJ_TOKEN=your-token-here

# IME package IDs (verify with: adb shell ime list -s)
TERMINAL_IME_ID=juloo.keyboard2/.Keyboard2
DEFAULT_IME_ID=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

### 2. Build and Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Grant Permissions

The app needs `WRITE_SECURE_SETTINGS` for automatic keyboard switching:

```bash
adb shell pm grant com.zellijconnect.app android.permission.WRITE_SECURE_SETTINGS
```

## Volume Key Scrolling

Volume keys can scroll the terminal, but require Zellij keybinding configuration.

### Zellij Configuration

Add to `~/.config/zellij/config.kdl` on your Zellij server:

```kdl
keybinds {
    shared {
        bind "Ctrl Shift Alt k" { ScrollUp; }
        bind "Ctrl Shift Alt j" { ScrollDown; }
    }
}
```

Then restart your Zellij sessions for the config to take effect.

### How it works

- **Volume Up** - Sends `Ctrl+Shift+Alt+K` (scroll up)
- **Volume Down** - Sends `Ctrl+Shift+Alt+J` (scroll down)

## UI Controls

| Control | Action |
|---------|--------|
| **ESC button** | Sends Escape key to terminal |
| **+ button** | Opens new Zellij session tab |
| **Browser button** | Opens phone browser to server:5173 |
| **Fullscreen button** | Toggles immersive mode |
| **X on tab** | Detaches Zellij session and closes tab |
| **Swipe up on tab** | Same as X button |

## Clipboard Integration

ZellijConnect supports bidirectional clipboard sync between Android and your Zellij sessions using OSC 52 escape sequences.

### How it works

- **Paste (Android → Terminal)**: Long-press in terminal → Paste, or use keyboard paste
- **Copy (Terminal → Android)**: Terminal apps using OSC 52 will copy to Android clipboard

### Server-side Configuration

For clipboard sync from terminal to Android, Zellij must forward OSC 52 sequences. This is the default behavior, but ensure your config doesn't override it:

```kdl
# ~/.config/zellij/config.kdl

# Leave copy_command unset to use OSC 52 (default)
# copy_command "pbcopy"  # Don't set this for web client use

copy_clipboard "system"
copy_on_select true
```

### Testing OSC 52

Test clipboard copy from terminal to Android:

```bash
# This should copy "hello" to your Android clipboard
printf '\e]52;c;%s\a' "$(echo -n 'hello' | base64)"
```

### Supported Apps

Apps that use OSC 52 will automatically sync to Android clipboard:
- Neovim (with `set clipboard=unnamedplus`)
- tmux (with `set -g set-clipboard on`)
- vim-oscyank plugin
- Most modern terminal apps with clipboard support

## Building

Requirements:
- Android SDK 35 (Android 15)
- Java 21
- Gradle 8.x

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install
./gradlew installDebug

# View logs
adb logcat -s ZellijConnect:V
```

## Session Status Server (Optional)

The `scripts/` directory contains a session status server that provides:

- List of active Zellij sessions via REST API
- Claude Code activity status tracking
- Git branch status per session

### Quick Install (on Zellij server)

```bash
cd scripts/
./install.sh
```

See `scripts/README.md` for detailed setup instructions.

### API Endpoint

```
GET http://your-server:7601/api/sessions
```

Returns session info including Claude status and git branch info.

## Architecture

- `MainActivity` - Main UI, tab management, keyboard/volume handling
- `TabManager` - Tab state and SharedPreferences persistence
- `TabAdapter` - RecyclerView adapter for tab strip
- `WebViewPool` - WebView lifecycle and JavaScript bridge
- `IMESwitchManager` - Automatic keyboard switching via Settings.Secure
- `KeepAliveService` - Foreground service with wake lock
- `ConnectionMonitor` - Error detection and retry banner
- `ClipboardBridge` - OSC 52 clipboard sync (Zellij <-> Android)
- `AppConfig` - BuildConfig value access

## License

MIT
