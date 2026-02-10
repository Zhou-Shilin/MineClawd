# Changelog

All notable changes to this project are documented in this file.

## [1.2.0] - 2026-02-10

### Added

- Runtime dynamic placeholder registry with `30` item slots, `30` block slots, and `30` fluid slots.
- Dynamic content tool suite:
- `list-dynamic-content`
- `register-dynamic-item`
- `register-dynamic-block`
- `register-dynamic-fluid`
- `update-dynamic-item`
- `update-dynamic-block`
- `update-dynamic-fluid`
- `unregister-dynamic-content`
- Client-side dynamic model/render pipeline for runtime material substitution and fluid tint rendering.
- KubeJS callback bridge APIs:
- `global.mineclawd.requestWithSession(player, session_ref, request)`
- `global.mineclawd.requestOneShot(request, context)`
- aliases `callWithSession` and `callOneShot`.
- `/mineclawd history` command with a client-opened written book view and rich text rendering.
- `ask-user-question` tool with client question popup UI, option buttons, free-form response, and timeout handling.
- LLM request recovery UX with clickable `[Retry]` and `[Adjust Prompt]` chat actions.
- `/mineclawd retry <token>` command and failed-request token tracking.
- `/mineclawd sessions repair [session]` command to repair malformed session history turns.
- `sync-command-tree` tool to refresh Brigadier command trees for online players.

### Changed

- Dynamic registry runtime mode is now configurable via `dynamic-registry-mode`:
- `AUTO`: enabled in single-player runtime, disabled on dedicated servers by default.
- `ENABLED`: forces runtime placeholders on (dedicated servers warn that clients must install MineClawd).
- `DISABLED`: fully off.
- Dynamic placeholder state is now persisted and restored across relog/restart, and synced to players on join.
- Dynamic fluid defaults now keep water-like movement behavior (flow speed, tick rate, level decrease, collision profile).
- Added water fluid-tag compatibility entries for all dynamic still/flowing fluids.
- System prompt now conditionally appends dynamic-registry guidance only when runtime placeholders are enabled.
- System prompt now documents KubeJS callback usage and session-binding constraints.
- Session prompt context now includes current session id/token for callback wiring.
- LLM error handling now rolls back failed prompts from session history to avoid duplicate retries.
- KubeJS reload error parsing now filters common success lines (`0 errors`, `0 warnings`) to reduce false positives.

### Fixed

- Fixed dynamic creative-tab visibility desync after rejoin by synchronizing server-side dynamic state to clients.
- Fixed dynamic block/item/fluid runtime properties being lost after restart by persisting registry payload in world state.
- Fixed Vertex AI function-call/function-response turn mismatch recovery with in-session normalization and repair command support.
- Fixed `/mineclawd history` client UX to open the book screen directly without requiring an inventory slot.

## [1.1.0] - 2026-02-07

### Added

- Persistent session storage under `gameDir/mineclawd/sessions/`.
- Full session management commands:
- `/mineclawd sessions new`
- `/mineclawd sessions list`
- `/mineclawd sessions resume <session>`
- `/mineclawd sessions remove <session>`
- `/mineclawd new` alias for `/mineclawd sessions new`.
- Session reference parsing with both `uuid` and `uuid-title`.
- First-turn session title generation via provider-specific summarize models.
- Persona system with `mineclawd/souls/` storage.
- New persona commands:
- `/mineclawd persona`
- `/mineclawd persona <soul>`
- Built-in souls: `default` and `yuki`.
- Expanded agent toolset:
- `apply-instant-server-script`
- `execute-command`
- `list-server-scripts`
- `read-server-script`
- `write-server-script`
- `delete-server-script`
- `reload-game`
- KubeJS reload error capture and return-to-LLM flow for self-repair loops.
- Mod icon integration and Modrinth link metadata.

### Changed

- Improved system prompt guidance for progress updates, tool strategy, and MineDown support.
- Tool-call limit now defaults to `16`, with an enable/disable toggle.
- Agent/player chat presentation improved:
- Prompt echo format: `<playername> @MineClawd Original Prompt`
- Styled `[MineClawd]` prefix
- Markdown/MineDown message rendering
- Debug logging expanded for LLM outputs and tool activity.

### Fixed

- Fixed command routing and aliases for prompt/config/session flows.
- Fixed blank YACL config UI and key visibility behavior.
- Improved internal command execution path so real tool errors can be surfaced back to the model.

## [1.0.0] - 2026-02-06

### Added

- Initial public release for Fabric `1.20.1`.
- OpenAI and Google Vertex AI provider support.
- YACL config UI with provider-specific fields and API key masking.
- Core in-game commands:
- `/mineclawd prompt <request>`
- `/mclawd <request>`
- `/mineclawd config`
- Base KubeJS integration:
- Auto-generated `kubejs/server_scripts/mineclawd-internal-api.js`
- Internal execution command `/_exec_kubejs_internal <code>`
- Tool-loop execution model for request handling.

### Changed

- Project metadata, description, and license aligned for public release.
