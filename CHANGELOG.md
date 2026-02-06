# Changelog

All notable changes to this project are documented in this file.

## [1.0.0] - 2026-02-06

- First public release of MineClawd for Fabric `1.20.1`.
- Added OpenAI and Google Vertex AI provider support.
- Added YACL-based configuration with API key masking and provider-specific fields.
- Added in-game commands:
- `/mineclawd prompt <request>`
- `/mclawd <request>`
- `/mineclawd config`
- `/mineclawd new`
- Added KubeJS integration:
- Auto-generation of `mineclawd-internal-api.js`
- Internal command `/_exec_kubejs_internal <code>`
- Tool-call loop for agent execution and self-correction
- Added debug logging mode for LLM responses and tool calls.
- Added optional tool-call round limiting with configurable cap.
- Added MineDown/Markdown-rendered agent output and styled `[MineClawd]` prefix.
- Updated project metadata and license to Apache 2.0.
