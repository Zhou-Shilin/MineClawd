# MineClawd

> [!WARNING]
> MineClawd is an experimental project. Since it allows the agent to execute JavaScript code in-game, theoretically it can be exploited to run arbitrary Java code through `Java.loadClass()`, leading to potential security risks, including but not limited to data loss, corruption, or unauthorized access.
> Use at your own risk and ensure you understand the implications of granting OP access to the mod.

Bring OpenClaw into Minecraft! The Minecraft Agent that actually do things.

MineClawd is a Fabric mod that connects in-game chat commands to an LLM agent capable of tool use through KubeJS.

## Features

- In-game agent chat command with session memory.
- LLM providers: `OpenAI` (including OpenAI-compatible endpoints), `Google Vertex AI`.
- KubeJS tool loop with auto-generated `kubejs/server_scripts/mineclawd-internal-api.js`.
- Internal execution command: `/_exec_kubejs_internal <code>`.
- Tool outputs and real execution errors are returned to the LLM for self-correction.
- YACL config UI (via Mod Menu button or command), with masked API key display.
- Debug mode with detailed LLM/tool logs.
- Optional tool-call round limit.

## Quick Start

This is the fastest path from zero to a first successful chat.

1. Install Minecraft `1.20.1`, Fabric Loader, and Java `17+`.
2. Put these mods in your `mods/` folder: `MineClawd`, `Fabric API`, `KubeJS`, `Architectury API`, `Rhino`, `YetAnotherConfigLib (YACL v3)`, and optionally `Mod Menu`.
3. Start the game once, enter a world/server where you are OP.
4. Configure MineClawd in-game via `Mod Menu -> MineClawd -> Configure` or command `/mineclawd config`.
5. Select provider and fill credentials:
OpenAI fields: `endpoint`, `apiKey`, `model`.
Vertex AI fields: `vertexEndpoint`, `vertexApiKey`, `vertexModel`.
6. Run your first prompt: `/mclawd hi`.

If setup is correct, MineClawd replies in chat and keeps session context.

## Commands

- `/mineclawd prompt <request>`: Send a request to the agent.
- `/mclawd <request>`: Short alias for prompt.
- `/mineclawd config`: Open config UI.
- `/mineclawd new`: Clear current session history.

Notes:
- Agent commands are OP-only (`permission level 2`).
- Prompt echo format in chat: `<playername> @MineClawd Original Prompt`.

## Security

- MineClawd can execute generated KubeJS JavaScript through an internal command and `eval`.
- Keep OP access restricted.
- Use API keys with least privilege and rotate keys if exposed.

## Credits

- **Project Author**: Zhou-Shilin (BaimoQilin)
- **Coding Support**: OpenAI Codex (gpt-5.3-codex), OpenCode (claude-opus-4-6)
- **Libraries and Upstream Projects**:
- Fabric Loader, Fabric API, Yarn mappings by the FabricMC community and contributors.
- KubeJS by the KubeJS maintainers and contributors.
- YetAnotherConfigLib (YACL) by isXander and contributors.
- Mod Menu by TerraformersMC contributors.
- MineDown Adventure by Phoenix616 and contributors.
- Adventure (Kyori) by kyori and contributors.
- Gson by Google.

## License

Apache License 2.0. See `LICENSE`.
