<div align="center">
    <img src="public/mascot.png" width="150" alt="MineClawd Mascot">

# MineClawd

<p>
    <strong>Bring OpenClaw into Minecraft! The Minecraft Agent that actually do things.</strong>
</p>

<p>Mascot: <strong>Yuki</strong></p>

[![Modrinth](https://img.shields.io/badge/Modrinth-RWXmIPlB-00AF5C?style=flat&logo=modrinth)](https://modrinth.com/project/RWXmIPlB)

</div>

https://github.com/user-attachments/assets/baf1b5c6-56be-43ee-90d1-097e9fbc75fe

> [!WARNING]
> MineClawd is an experimental project. It can execute generated JavaScript in-game. Treat OP access and API credentials as sensitive, and only use this mod in environments you trust.

MineClawd is a Fabric mod that turns in-game chat into an agent workflow. You describe what you want, and the agent can inspect, modify, and automate gameplay on your server.

## Features

- **Natural language control**: ask for gameplay help, world edits, automation, or admin tasks directly in chat.
- **Real actions, not fixed macros**: MineClawd can inspect state and adapt steps while it works.
- **Persistent automation support**: it can write and update server-side logic for commands, recipes, drops, listeners, and tick behavior.
- **Session management built for long tasks**: create, list, resume, and remove sessions without losing context.
- **Persona system**: switch between `default`, `yuki`, or your own custom souls.
- **Rich chat UX**: readable progress updates and Markdown/MineDown formatted replies in-game.
- **Multi-provider LLM support**: OpenAI-compatible endpoints and Google Vertex AI.
- **Practical guardrails**: optional tool-call limits, debug logs, and OP-only command access.

*PS: MineClawd executes generated code instead of relying on preset operations, so its capability surface is effectively open-ended.*

## Quick Start

1. Install Minecraft `1.20.1`, Fabric Loader, and Java `17+`.
2. Put these mods in `mods/`: `MineClawd`, `Fabric API`, `KubeJS`, `Architectury API`, `Rhino`, `YetAnotherConfigLib (YACL v3)`, and optionally `Mod Menu`.
3. Start the game once and join a world/server where you have OP.
4. Open config with `Mod Menu -> MineClawd -> Configure` or `/mineclawd config`.
5. Choose provider and fill credentials:
- OpenAI: `endpoint`, `apiKey`, `model`, `summarizeModel`
- Vertex AI: `vertexEndpoint`, `vertexApiKey`, `vertexModel`, `vertexSummarizeModel`
6. Send your first prompt: `/mclawd hello`.

If setup is correct, MineClawd replies in chat and keeps session memory.

## Commands

- `/mineclawd prompt <request>`: send a request to the agent.
- `/mclawd <request>`: short alias for prompt.
- `/mineclawd config`: open the config screen.
- `/mineclawd sessions new`: create and switch to a new session.
- `/mineclawd new`: alias of `/mineclawd sessions new`.
- `/mineclawd sessions list`: list saved sessions (id, title, last modified).
- `/mineclawd sessions resume <session>`: switch active session (`uuid` or `uuid-title`).
- `/mineclawd sessions remove <session>`: delete a session (`uuid` or `uuid-title`).
- `/mineclawd persona`: show active persona and available souls.
- `/mineclawd persona <soul>`: switch persona.

## Storage Paths

- Sessions: `gameDir/mineclawd/sessions/`
- Souls: `gameDir/mineclawd/souls/`

## Security

- MineClawd can execute generated KubeJS JavaScript and run commands.
- Restrict OP access.
- Use least-privilege API keys and rotate them if exposed.

## Credits

- **Project Author**: Zhou-Shilin (BaimoQilin)
- **Coding Support**: OpenAI Codex
- **Libraries and Upstream Projects**:
- Fabric Loader, Fabric API, and Yarn mappings by FabricMC contributors.
- KubeJS by the KubeJS maintainers and contributors.
- YetAnotherConfigLib (YACL) by isXander and contributors.
- Mod Menu by TerraformersMC contributors.
- MineDown Adventure by Phoenix616 and contributors.
- Adventure (Kyori) by kyori and contributors.
- Gson by Google.

## License

Apache License 2.0. See `LICENSE`.
