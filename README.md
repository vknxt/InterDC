# InterDC (v1.2)

Integrate your Discord server into an interactive screen-based interface.

InterDC connects Discord channels to visual panels, supporting live updates and localized UX.

---

## Project Status

**Current version:** `1.2`  
**State:** stable for core production usage (sync, render, and main commands), with an active roadmap for advanced UX and moderation workflows.

---

## What Has Been Added (Implemented)

### Core / Integration

- Visual rendering of Discord channels on interactive screens
- Real-time sync for channels, messages, and members
- Live Discord mode (JDA) + mock fallback mode
- SQLite persistence for screens, themes, and state

### UX / Panel

- Visual style presets: `discord`, `glass`, `classic`, `ultra`
- Adaptive text contrast for better readability on light/dark backgrounds
- Optional member list panel
- Click interaction flow

### Operations / Administration

- Screen management commands (`create`, `link`, `link2`, `move`, `remove`, `reload`)
- `/dc health` diagnostics (Discord/DB/cache/coalescer/flags snapshot)
- `/dc perf` runtime performance counters
- Feature flags for internal services
- Discord event coalescing to reduce burst update pressure

### Localization

- Native support for 7 languages: EN, PT-BR, ES, FR, DE, IT, JA
- Locale fallback + optional automatic client-locale mode

---

## What Is Missing (Next Steps)

- Richer in-panel interaction actions (moderation shortcuts and advanced controls)
- More customizable visual themes
- Broader automated test coverage for render and interaction flows
- Better operational observability docs and ready-to-use dashboards
- Improved onboarding/setup wizard for Discord token and permissions

---

## How It Works

```mermaid
graph LR
  A[Discord Server] <--> B[Discord Bot API]
  B <--> C[InterDC System]
  C <--> D[Client Interface]
  D <--> E[Users]
