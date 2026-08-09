# Lumi Core - Phase 1 (Windows Media Adapter Prototype)

## Objective

Determine whether Windows exposes enough APIs to allow Lumi to become a universal playback synchronization engine.

The objective is **NOT** to build the final application.

The objective is to verify that Windows can reliably:

- Detect active media sessions
- Read playback information
- Control playback
- Receive playback events

---

# Vision

Lumi is **NOT** a media player.

Lumi never:

- Decodes video
- Streams media
- Uploads media
- Downloads media
- Renders video

Instead, Lumi acts as a synchronization engine that communicates with the operating system while existing media players continue doing the playback.

Examples:

- Screenbox
- VLC
- Windows Media Player
- Movies & TV
- Chrome
- Edge
- Netflix
- Prime Video
- YouTube

---

# Technology

Language:
- Rust

Reason:
- Native performance
- Small executable
- Cross-platform
- Excellent async networking
- Native Windows API support
- Easy future expansion to Android, macOS and iOS

UI:
None.

The prototype will be CLI only.

---

# Phase 1

Create a Rust CLI application.

The application should:

1. Detect all active Windows media sessions.
2. Print every detected session.
3. Read metadata.
4. Read playback state.
5. Read playback timeline.
6. Attempt playback control.

No networking.

No GUI.

No backend.

No database.

---

# Information to Display

For every media session print:

Application Name

Application ID

Media Title

Artist (if available)

Playback State

Current Position

Duration

Capabilities

Can Play

Can Pause

Can Seek

---

# Commands to Test

The prototype must verify whether the following operations work.

Read

- Current position
- Duration
- Playback state
- Metadata

Write

- Play
- Pause
- Seek

Optional

- Stop
- Next
- Previous

---

# Event Detection

Verify whether Windows emits events for

- Playback started
- Playback paused
- Playback resumed
- Timeline changed
- Session opened
- Session closed
- Metadata changed

Print every event to the console.

---

# Target Applications

Test against:

Priority 1

- Screenbox
- VLC

Priority 2

- Windows Media Player
- Movies & TV

Priority 3

- Chrome
- Edge

Inside browser:

- YouTube
- Netflix
- Prime Video

---

# Success Criteria

The prototype is considered successful if it can:

✓ Detect every supported media session

✓ Read playback information

✓ Read timeline

✓ Receive playback events

✓ Play

✓ Pause

✓ Seek

---

# Out of Scope

Do NOT implement

- Networking
- Synchronization
- Backend
- WebSockets
- Authentication
- Database
- GUI
- File loading
- Video rendering

---

# Deliverables

The AI agent should produce:

1. Rust project

2. Well-structured source code

3. Clear module separation

4. Build instructions

5. Test instructions

6. Console output showing detected media sessions

---

# Important

Avoid unofficial methods.

Do NOT:

- Simulate keyboard shortcuts
- Use OCR
- Use UI Automation unless absolutely required
- Read application memory
- Inject DLLs
- Hook DirectX
- Reverse engineer media players

Use official Windows APIs wherever possible.

If an application does not expose playback controls through Windows, report it as unsupported instead of implementing hacks.

---

# Future Phases (Not Now)

Phase 2
- Networking

Phase 3
- Playback synchronization

Phase 4
- Android Media Adapter

Phase 5
- macOS Media Adapter

Phase 6
- iOS Media Adapter

The only goal of this project is to determine whether a clean, OS-level media synchronization architecture is technically feasible.