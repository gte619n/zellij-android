# Feature Specification: SFTP File Browser with Rich Viewer

**Author:** Claude (PM/Senior Dev)
**Date:** 2026-02-16
**Status:** Phase 1 Code Complete (needs device testing)
**Branch:** TBD

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Decisions](#2-design-decisions)
3. [Architecture](#3-architecture)
4. [Phase 1: SFTP Connection + Directory Browsing](#4-phase-1-sftp-connection--directory-browsing)
5. [Phase 2: Text/Markdown Viewer with Syntax Highlighting](#5-phase-2-textmarkdown-viewer-with-syntax-highlighting)
6. [Phase 3: Rich Content (Images) + Copy/Share Actions](#6-phase-3-rich-content-images--copyshare-actions)
7. [Implementation Tracker](#7-implementation-tracker)
8. [Dependencies](#8-dependencies)
9. [Risk Register](#9-risk-register)

---

## 1. Overview

### Goal

Add a lightweight SFTP-powered file browser to ZellijConnect that lets users browse remote directories and view files (markdown, source code, images) in a native Android tab alongside their terminal sessions.

### User Story

> As a developer using ZellijConnect on my Android device, I want to tap a folder icon in the tab bar to open a file browser tab that defaults to my session's working directory, so I can browse and read files on the server without switching to a terminal `cat` or `less` command.

### Key Constraints

- **Read-only** — no file creation, deletion, upload, or editing
- **Session-aware** — each file browser tab is bound to a Zellij session and its working directory
- **Direct SFTP** — connects from the Android device to the server over SSH, not via the metadata server
- **Ed25519 key auth only** — reuses the existing app keypair (PKCS8 to OpenSSH format conversion)
- **Persistent connection** — one SFTP connection per unique host, kept alive across tab switches

---

## 2. Design Decisions

These decisions were made during a detailed interview process. Each records the options considered and the rationale.

| # | Decision | Choice | Alternatives Considered |
|---|----------|--------|------------------------|
| D1 | Transport protocol | Direct SFTP from Android | Extend HTTPS metadata server; Hybrid HTTPS+SFTP |
| D2 | Session binding | Session-aware (file browser tab bound to a Zellij session) | Session-independent; Session-aware with detach |
| D3 | File type support | Rich viewer (text + syntax highlighting + images) | Text/markdown only; Text + syntax highlighting only |
| D4 | Write operations | Read-only | Read + basic write; Full editor |
| D5 | Authentication | SSH keys only (existing Ed25519 keypair) | Keys + password fallback; Keys + agent forwarding |
| D6 | SFTP target config | Derive hostname from Zellij base URL + configurable SSH port in Settings | Separate SFTP config; Per-session config |
| D7 | Tab architecture | Dual content type (TERMINAL vs FILE_BROWSER) | WebView-based browser; Overlay/dialog approach |
| D8 | Icon placement | Between add-tab (+) button and Settings gear | After Settings; Context-dependent visibility |
| D9 | Host key verification | Trust-on-first-use (TOFU) | Pre-configure in Settings; Skip verification |
| D10 | Large file handling | Truncate with 'load more' (first 500 lines) | Paginated view; Smart threshold |
| D11 | Connection lifecycle | Persistent per-host (one connection per unique SSH host) | Per-session; On-demand with cache |
| D12 | Browse UX | Flat list + breadcrumb path bar | Tree view; Flat list + path bar + favorites |
| D13 | File actions | Copy to clipboard + Android share sheet | Copy + open-in-terminal; All three |
| D14 | Image handling | On-demand full download on tap | Lazy thumbnails; Metadata only until tap |
| D15 | SSH username | Settings field with smart default | Prompt on first connect; Derive from CWD path |
| D16 | Directory refresh | Auto-refresh listing on tab switch | Pull-to-refresh only; Auto-refresh + stale indicator |
| D17 | Hidden files | Hidden by default, toggle button to show | Show everything; Smart filter with ignore list |
| D18 | Error handling | Auto-reconnect with toast (3 attempts, then error banner) | Error banner with retry; Inline error state |
| D19 | SFTP library | JSch mwiede fork (v2.27.7, pure Java, lightweight) | SSHJ (heavier, needs BouncyCastle); Apache MINA SSHD |
| D20 | Markdown library | Markwon (3.3k stars, native Spannables, no WebView) | flexmark-java; WebView-based rendering |
| D21 | Key format | Reuse existing Ed25519 keypair with PKCS8-to-OpenSSH conversion | Separate SFTP keypair; Re-generate in JSch format |
| D22 | Icon style | Material folder icon (24dp, white fill, surface tint) | File/document icon; Folder-open icon |
| D23 | Sort order | Folders first (alphabetical), then files (alphabetical) | Pure alphabetical; Modified time descending |
| D24 | Phasing | 3 phases (tight) | 4 phases (balanced); 2 phases (aggressive) |

---

## 3. Architecture

### 3.1 System Diagram

```
┌─────────────────────────────────────────────────────┐
│  Android App                                         │
│                                                      │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │TabManager │──│ WebViewPool  │  │ SftpManager   │  │
│  │(type field│  │ (TERMINAL    │  │ (FILE_BROWSER │  │
│  │per tab)   │  │  tabs only)  │  │  tabs only)   │  │
│  └────┬─────┘  └──────────────┘  └───────┬───────┘  │
│       │                                   │          │
│       ▼                                   ▼          │
│  ┌──────────────────────────────────────────────┐    │
│  │           MainActivity                        │    │
│  │  ┌─────────────┐  ┌──────────────────────┐   │    │
│  │  │  WebView     │  │  FileBrowserView     │   │    │
│  │  │  (terminal)  │  │  ┌────────────────┐  │   │    │
│  │  │              │  │  │ BreadcrumbBar   │  │   │    │
│  │  │              │  │  ├────────────────┤  │   │    │
│  │  │              │  │  │ DirectoryList   │  │   │    │
│  │  │              │  │  │ (RecyclerView)  │  │   │    │
│  │  │              │  │  ├────────────────┤  │   │    │
│  │  │              │  │  │ FileViewer      │  │   │    │
│  │  │              │  │  │ (Markwon/Image) │  │   │    │
│  │  │              │  │  └────────────────┘  │   │    │
│  │  └─────────────┘  └──────────────────────┘   │    │
│  └──────────────────────────────────────────────┘    │
│                           │                          │
│                    SshKeyManager                     │
│                  (PKCS8 → OpenSSH)                   │
└───────────────────────────┼──────────────────────────┘
                            │ SSH/SFTP (Ed25519)
                            ▼
                    ┌───────────────┐
                    │  Remote Server │
                    │  (SSH on :22)  │
                    └───────────────┘
```

### 3.2 New Classes

| Class | Responsibility |
|-------|---------------|
| `SftpManager` | Manages JSch SFTP connections (connect, disconnect, reconnect, pooling per host). Thread-safe. |
| `SftpFileEntry` | Data class: name, path, size, modifiedTime, isDirectory, permissions |
| `FileBrowserView` | Custom compound View containing breadcrumb bar, directory RecyclerView, and file viewer area |
| `FileBrowserAdapter` | RecyclerView adapter for directory listings (file icon, name, size, date) |
| `BreadcrumbBar` | Horizontal scrolling path segments, each tappable for navigation |
| `FileViewerFragment` | Renders file content: Markwon for text/markdown, ImageView for images |
| `SftpHostKeyStore` | TOFU host key storage (SharedPreferences-backed) |

### 3.3 Modified Classes

| Class | Changes |
|-------|---------|
| `TabManager` | Add `TabType` enum (TERMINAL, FILE_BROWSER). Add `sessionName` field to `Tab`. Add `linkedSessionName` for file browser tabs. |
| `MainActivity` | Handle dual content types in `showViewForTab()`. Wire file browser icon. Manage `FileBrowserView` lifecycle alongside `WebViewPool`. |
| `AppConfig` | Add `getSshPort()`, `setSshPort()`, `getSshUsername()`, `setSshUsername()` with smart defaults. |
| `SshKeyManager` | Add `getPrivateKeyOpenSSH()` method for PKCS8 → OpenSSH format conversion for JSch. |
| `SettingsDialog` | Add SSH port and SSH username fields to server configuration section. |
| `TabAdapter` | Show different icon/color for FILE_BROWSER tabs vs TERMINAL tabs. |

### 3.4 New Layouts

| Layout | Purpose |
|--------|---------|
| `view_file_browser.xml` | Main file browser compound view (breadcrumb + list + viewer) |
| `item_file_entry.xml` | Single row in directory listing (icon + name + size + date) |
| `view_file_content.xml` | File viewer with toolbar (copy/share buttons) + scrollable content area |
| `view_breadcrumb.xml` | Horizontal breadcrumb path bar |

### 3.5 New Drawables

| Drawable | Purpose |
|----------|---------|
| `ic_file_browser.xml` | Folder icon for tab bar (24dp Material vector) |
| `ic_file_generic.xml` | Generic file icon for listings |
| `ic_file_text.xml` | Text/code file icon |
| `ic_file_image.xml` | Image file icon |
| `ic_file_markdown.xml` | Markdown file icon |
| `ic_toggle_hidden.xml` | Eye/hidden-files toggle icon |

### 3.6 SFTP Connection Flow

```
User taps folder icon
  → Get active tab's sessionName
  → Look up session's workingDirectory from SessionInfo cache
  → Check if SftpManager has active connection for this host
    → YES: reuse connection
    → NO: establish new connection
      → Extract hostname from AppConfig.getBaseUrl()
      → Port from AppConfig.getSshPort() (default 22)
      → Username from AppConfig.getSshUsername()
      → Private key from SshKeyManager.getPrivateKeyOpenSSH()
      → Host key verification via SftpHostKeyStore (TOFU)
      → Connect + authenticate
  → Create FILE_BROWSER tab linked to session
  → Load directory listing for workingDirectory
  → Display in FileBrowserView
```

### 3.7 Tab Type Switching

```
showViewForTab(Tab tab):
  if tab.type == TERMINAL:
    hide all FileBrowserViews
    show WebView from WebViewPool
  else if tab.type == FILE_BROWSER:
    hide all WebViews
    show/create FileBrowserView for this tab
    if switching TO file browser: auto-refresh directory listing
```

---

## 4. Phase 1: SFTP Connection + Directory Browsing

**Goal:** User can tap the folder icon, connect via SFTP, and browse directories.

### 4.1 Tasks

| ID | Task | Files | Status | Tested | Pushed |
|----|------|-------|--------|--------|--------|
| P1.1 | Add JSch mwiede fork dependency to build.gradle | `app/build.gradle` | [x] | [ ] | [ ] |
| P1.2 | Add SSH port and username fields to AppConfig | `AppConfig.java` | [x] | [ ] | [ ] |
| P1.3 | Add SSH port and username to Settings dialog | `SettingsDialog.java`, `dialog_settings.xml`, `strings.xml` | [x] | [ ] | [ ] |
| P1.4 | Add PKCS8-to-OpenSSH private key conversion in SshKeyManager | `SshKeyManager.java` | SKIPPED (JSch loads PKCS8 directly via addIdentity) | [ ] | [ ] |
| P1.5 | Create SftpHostKeyStore (TOFU host key verification) | `SftpHostKeyStore.java` (new) | [x] | [ ] | [ ] |
| P1.6 | Create SftpManager (connection pooling, connect/disconnect/reconnect) | `SftpManager.java` (new) | [x] | [ ] | [ ] |
| P1.7 | Create SftpFileEntry data class | `SftpFileEntry.java` (new) | [x] | [ ] | [ ] |
| P1.8 | Add TabType enum to TabManager.Tab, add linkedSessionName field | `TabManager.java` | [x] | [ ] | [ ] |
| P1.9 | Create ic_file_browser.xml folder icon drawable | `res/drawable/ic_file_browser.xml` (new) | [x] | [ ] | [ ] |
| P1.10 | Add file browser button to activity_main.xml (between + and Settings) | `activity_main.xml` | [x] | [ ] | [ ] |
| P1.11 | Create item_file_entry.xml layout | `res/layout/item_file_entry.xml` (new) | [x] | [ ] | [ ] |
| P1.12 | Create BreadcrumbBar view | Inline in `FileBrowserView.java` + `view_file_browser.xml` | [x] | [ ] | [ ] |
| P1.13 | Create FileBrowserAdapter (directory listing) | `FileBrowserAdapter.java` (new) | [x] | [ ] | [ ] |
| P1.14 | Create FileBrowserView (compound: breadcrumb + RecyclerView) | `FileBrowserView.java` (new), `view_file_browser.xml` (new) | [x] | [ ] | [ ] |
| P1.15 | Create file type icons (generic, folder variants) | `res/drawable/ic_file_*.xml` (new) | [x] | [ ] | [ ] |
| P1.16 | Wire folder button in MainActivity, handle dual tab types | `MainActivity.java` | [x] | [ ] | [ ] |
| P1.17 | Update TabAdapter for FILE_BROWSER tab visual distinction | `TabAdapter.java` | [x] | [ ] | [ ] |
| P1.18 | Implement hidden files toggle (default hidden, toolbar button to show) | `FileBrowserView.java` | [x] | [ ] | [ ] |
| P1.19 | Implement auto-reconnect with toast on SFTP errors | `SftpManager.java` | [x] | [ ] | [ ] |
| P1.20 | Implement auto-refresh directory listing on tab switch | `MainActivity.java`, `FileBrowserView.java` | [x] | [ ] | [ ] |
| P1.21 | Build and verify on device | — | [x] (builds) | [ ] | [ ] |

### 4.2 Acceptance Criteria

- [ ] Folder icon visible in tab bar between + and Settings
- [ ] Tapping folder icon when a terminal session is active opens a new FILE_BROWSER tab
- [ ] Tab label shows directory name, tab strip distinguishes browser tabs from terminal tabs
- [ ] SFTP connects using existing Ed25519 key (user must have added public key to server)
- [ ] Host key stored on first connect (TOFU), warning shown if key changes
- [ ] Directory listing shows folders first (alphabetically), then files (alphabetically)
- [ ] Each entry shows: icon, name, size (human-readable), modified date
- [ ] Tapping a folder navigates into it, breadcrumb updates
- [ ] Tapping breadcrumb segments navigates to that directory
- [ ] Hidden files hidden by default, toggle button reveals them
- [ ] Switching back to file browser tab auto-refreshes the listing
- [ ] SFTP connection persists across tab switches to the same host
- [ ] Network errors trigger auto-reconnect (3 attempts) with toast notification
- [ ] SSH port configurable in Settings (default 22)
- [ ] SSH username configurable in Settings

---

## 5. Phase 2: Text/Markdown Viewer with Syntax Highlighting

**Goal:** User can tap a file in the directory listing to view its contents with syntax highlighting.

### 5.1 Tasks

| ID | Task | Files | Status | Tested | Pushed |
|----|------|-------|--------|--------|--------|
| P2.1 | Add Markwon core + syntax-highlight + tables + strikethrough + task-list dependencies | `app/build.gradle` | [ ] | [ ] | [ ] |
| P2.2 | Add Prism4j bundler for language grammars | `app/build.gradle` | [ ] | [ ] | [ ] |
| P2.3 | Create FileViewerView (toolbar + scrollable Markwon-rendered content) | `FileViewerView.java` (new), `view_file_content.xml` (new) | [ ] | [ ] | [ ] |
| P2.4 | Implement file content fetching via SFTP (async, background thread) | `SftpManager.java` | [ ] | [ ] | [ ] |
| P2.5 | Implement file type detection (extension-based: .md, .py, .java, .rs, etc.) | `FileTypeDetector.java` (new) | [ ] | [ ] | [ ] |
| P2.6 | Implement Markwon markdown rendering with dark theme | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.7 | Implement Prism4j syntax highlighting for source code files | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.8 | Implement plain text rendering fallback for unrecognized file types | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.9 | Implement truncation: show first 500 lines with 'Load More' button | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.10 | Implement loading indicator while fetching file content | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.11 | Wire directory listing tap → file viewer transition (within FileBrowserView) | `FileBrowserView.java` | [ ] | [ ] | [ ] |
| P2.12 | Add back navigation from file viewer to directory listing | `FileBrowserView.java` | [ ] | [ ] | [ ] |
| P2.13 | Handle binary file detection: show "Binary file (X KB)" message instead of content | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P2.14 | Build and verify on device | — | [ ] | [ ] | [ ] |

### 5.2 Acceptance Criteria

- [ ] Tapping a text file in the listing opens the file viewer
- [ ] Markdown files (.md) rendered with full formatting: headers, bold, italic, links, code blocks, tables, task lists
- [ ] Source code files (.py, .java, .kt, .rs, .go, .js, .ts, .c, .cpp, .sh, .yml, .json, .toml, .xml, .html, .css) rendered with syntax highlighting
- [ ] Plain text files rendered in monospace font
- [ ] Files over 500 lines truncated with "Load More" button that loads the next 500 lines
- [ ] Loading spinner shown while file content is fetched
- [ ] Binary files show a descriptive message instead of garbled content
- [ ] Back button/gesture returns to directory listing
- [ ] Breadcrumb shows file name when viewing a file
- [ ] Dark theme consistent with app's Material 3 theme

### 5.3 Supported Languages (Prism4j)

Prism4j supports these languages out of the box via `@PrismBundle`:

| Language | Extensions |
|----------|-----------|
| Markdown | .md, .markdown |
| Python | .py |
| Java | .java |
| Kotlin | .kt, .kts |
| JavaScript | .js, .mjs |
| TypeScript | .ts, .tsx |
| JSON | .json |
| YAML | .yml, .yaml |
| XML/HTML | .xml, .html, .htm |
| CSS | .css |
| Shell/Bash | .sh, .bash, .zsh |
| Rust | .rs |
| Go | .go |
| C/C++ | .c, .cpp, .h, .hpp |
| SQL | .sql |
| TOML | .toml |

---

## 6. Phase 3: Rich Content (Images) + Copy/Share Actions

**Goal:** User can view images, copy file content to clipboard, and share files via Android share sheet.

### 6.1 Tasks

| ID | Task | Files | Status | Tested | Pushed |
|----|------|-------|--------|--------|--------|
| P3.1 | Implement image file detection and download via SFTP | `SftpManager.java`, `FileTypeDetector.java` | [ ] | [ ] | [ ] |
| P3.2 | Implement image rendering in FileViewerView (ImageView with zoom/pan) | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.3 | Add image file icons to directory listing | `FileBrowserAdapter.java` | [ ] | [ ] | [ ] |
| P3.4 | Implement "Copy to clipboard" action for text file content | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.5 | Implement Android share sheet integration (share file content or image) | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.6 | Add toolbar with copy/share buttons to file viewer | `view_file_content.xml`, `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.7 | Handle large image files gracefully (show size, confirm before download) | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.8 | Add file size display in viewer toolbar | `FileViewerView.java` | [ ] | [ ] | [ ] |
| P3.9 | Polish: loading states, error states, empty directory state | Various | [ ] | [ ] | [ ] |
| P3.10 | Build and verify on device | — | [ ] | [ ] | [ ] |

### 6.2 Acceptance Criteria

- [ ] Image files (.png, .jpg, .jpeg, .gif, .webp, .svg, .bmp) display inline in the viewer
- [ ] Images support pinch-to-zoom and pan gestures
- [ ] Copy button copies text file content to Android clipboard
- [ ] Share button opens Android share sheet with file content (text) or file (image)
- [ ] Large images (>5MB) show size and require tap to confirm download
- [ ] File size displayed in viewer toolbar
- [ ] Empty directories show "Empty directory" message
- [ ] All error states handled gracefully (permission denied, file not found, etc.)

---

## 7. Implementation Tracker

### Phase Summary

| Phase | Description | Tasks | Completed | Status |
|-------|-------------|-------|-----------|--------|
| Phase 1 | SFTP Connection + Directory Browsing | 21 | 20 | CODE COMPLETE (needs device test) |
| Phase 2 | Text/Markdown Viewer + Syntax Highlighting | 14 | 0 | NOT STARTED |
| Phase 3 | Rich Content (Images) + Copy/Share | 10 | 0 | NOT STARTED |
| **Total** | | **45** | **20** | |

### Git Integration

| Phase | Branch | PR | Merged |
|-------|--------|----|--------|
| Phase 1 | `feature/file-browser-sftp` | — | — |
| Phase 2 | `feature/file-browser-viewer` | — | — |
| Phase 3 | `feature/file-browser-rich` | — | — |

### Milestone Dates

| Milestone | Target | Actual |
|-----------|--------|--------|
| Phase 1 complete | TBD | — |
| Phase 2 complete | TBD | — |
| Phase 3 complete | TBD | — |

---

## 8. Dependencies

### New Gradle Dependencies

```groovy
// SFTP (Phase 1)
implementation 'com.github.mwiede:jsch:0.2.21'  // Use latest stable from mwiede fork

// Markdown rendering (Phase 2)
def markwonVersion = '4.6.2'
implementation "io.noties.markwon:core:$markwonVersion"
implementation "io.noties.markwon:ext-strikethrough:$markwonVersion"
implementation "io.noties.markwon:ext-tables:$markwonVersion"
implementation "io.noties.markwon:ext-tasklist:$markwonVersion"
implementation "io.noties.markwon:recycler:$markwonVersion"
implementation "io.noties.markwon:syntax-highlight:$markwonVersion"

// Syntax highlighting engine (Phase 2)
implementation 'io.noties:prism4j:2.0.0'
annotationProcessor 'io.noties:prism4j-bundler:2.0.0'
```

### Existing Dependencies Leveraged

- `java.security.KeyPairGenerator` (Ed25519) — key generation (already in use)
- `androidx.recyclerview` — directory listing (already in use)
- `com.google.android.material` — Material 3 theming (already in use)

### Server-Side Requirements

- SSH server running on the remote host (standard sshd)
- User's public key added to `~/.ssh/authorized_keys` on the server
- Session working directories exposed via session-status-server.py (already implemented)

---

## 9. Risk Register

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| R1 | PKCS8 → OpenSSH Ed25519 private key conversion is non-trivial in Java | HIGH | Research JSch's `KeyPair.load()` format support first. If JSch can load PKCS8 directly, no conversion needed. Fallback: write key in PEM format. |
| R2 | JSch Ed25519 support may have edge cases on Android | MEDIUM | Test early in Phase 1. The mwiede fork explicitly supports Ed25519. If issues arise, fall back to RSA keypair generation. |
| R3 | Large directories (10k+ entries) may cause SFTP listing to be slow | MEDIUM | Implement async loading with progress indicator. Consider pagination of SFTP readdir calls. |
| R4 | Markwon + Prism4j rendering of large files may cause ANR | MEDIUM | Truncation at 500 lines (D10) mitigates this. Render on background thread, post to UI. |
| R5 | SFTP connection drops during Tailscale network transitions | LOW | Auto-reconnect with 3 retries (D18) handles this. Exponential backoff: 1s, 2s, 4s. |
| R6 | Host key changes after server reinstall confuse users | LOW | Clear warning dialog explaining the situation. Option to accept new key or cancel. |
| R7 | Dual tab type increases TabManager complexity significantly | MEDIUM | Clean interface separation. FILE_BROWSER tabs don't touch WebViewPool; TERMINAL tabs don't touch SftpManager. |
| R8 | Memory pressure from simultaneous WebViews + file browser views | LOW | File browser views are lightweight (native Android views). Only active tab's view is in the hierarchy. |

---

## Appendix A: Interview Record

The following questions were asked and answered during the planning interview on 2026-02-16:

**Round 1 — Foundational:**
1. Transport: Direct SFTP from Android (not HTTPS server extension)
2. Session binding: Session-aware (bound to Zellij session)
3. File types: Rich viewer (text + syntax + images)
4. Write operations: Read-only

**Round 2 — Technical:**
5. Authentication: SSH keys only (existing Ed25519)
6. SFTP target: Derive hostname from Zellij URL + configurable SSH port
7. Tab architecture: Dual content type (TERMINAL vs FILE_BROWSER)
8. Icon placement: Between add-tab (+) and Settings gear

**Round 3 — Edge Cases:**
9. Host key verification: Trust-on-first-use (TOFU)
10. Large files: Truncate with 'load more' (500 lines)
11. Connection lifecycle: Persistent per-host
12. Browse UX: Flat list + breadcrumb path bar

**Round 4 — Interactions:**
13. File actions: Copy to clipboard + Android share sheet
14. Image handling: On-demand full download on tap
15. SSH username: Settings field with smart default
16. Directory refresh: Auto-refresh listing on tab switch

**Round 5 — Details:**
17. Hidden files: Hidden by default, toggle to show
18. Error handling: Auto-reconnect with toast (3 retries, then error banner)
19. SFTP library: JSch mwiede fork
20. Phasing: 3 phases (tight)

**Round 6 — Final:**
21. Key format: Reuse existing keypair with PKCS8-to-OpenSSH conversion
22. Icon style: Material folder icon
23. Sort order: Folders first (alpha), then files (alpha)
