# Changelog

All notable changes to this project are documented in this file.

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
