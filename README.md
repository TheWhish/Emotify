<div align="center">

# 😊 Emotify

**A small visual language for the moments when chat is one message too slow.**

[![Version 0.2.0](https://img.shields.io/badge/Version-0.2.0-5865F2?style=flat-square)](#installation)
[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-Client_%26_Server-EA6A47?style=flat-square)](https://neoforged.net/)
[![Paper & Purpur](https://img.shields.io/badge/Paper_%26_Purpur-Server-4A90E2?style=flat-square)](https://papermc.io/)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square)](LICENSE)

</div>

Emotify adds animated pixel-art reactions above players: a fast, quiet alternative to another chat message or a full-body emote.

Version **0.2.0** brings the same server-authoritative emotion system to **NeoForge, Paper, and Purpur** servers.

---

## ✨ Features

- **162 built-in reactions** with persistent favorites, localized search, and complete English and Russian translations.
- **Three signature presentations** — Elastic Pop, Ribbon Weave, and Lantern Release — selected evenly for varied but restrained animation.
- **World-aware rendering** that follows player poses, stays behind blocks, and respects first-person and visibility rules.
- **Movement-friendly picker** that does not take control away from the player while it is open.
- **Reduced Motion** mode with a static fade-only presentation.
- **Server-authoritative multiplayer** with cooldowns, permissions, visibility filtering, bounded payloads, and abuse protection.

Emotify keeps its visual language close to Minecraft: readable pixel sprites, smooth motion, and no particle noise competing with gameplay.

## 🌐 Compatibility

| Component               | Supported version |
|-------------------------| --- |
| Minecraft               | **1.21.1** |
| Client or Modded server | **NeoForge 21.1.234+** |
| Plugin server           | **Paper or Purpur 1.21.1** |
| Java                    | **21** |
| NeoForge runtime        | **Kotlin for Forge 5.12.x** |

NeoForge, Paper, and Purpur use the same Emotify protocol. Players without the client mod can still join normally, but they do not send or see reactions.

> [!IMPORTANT]
> Paper and Purpur support is server-side. Emotify **0.2.0** still uses a NeoForge client; Fabric and additional Minecraft versions are not part of this release.

## 📦 Installation

| Platform | Artifact | Location and requirements |
| --- | --- | --- |
| NeoForge | `emotify-0.2.0.jar` | `mods`; requires Kotlin for Forge `5.12.x` |
| Paper / Purpur | `emotify-paper-1.21-0.2.0.jar` | `plugins`; no NeoForge or Kotlin for Forge on the server |

Players on Paper or Purpur use the regular NeoForge client mod. Do not install both Emotify artifacts on the same server.

## 🛡️ Server administration

Paper and Purpur settings are stored in `plugins/Emotify/config.yml` and can be applied without restarting:

```text
/emotify reload
```

Invalid configuration is rejected without replacing the active settings. A successful reload preserves connected sessions, active cooldowns, and abuse-protection state.

### Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `emotify.use` | Everyone | Send reactions |
| `emotify.receive` | Everyone | Receive reactions from other players |
| `emotify.admin.reload` | Operators | Reload the plugin configuration |

The plugin uses the standard Bukkit permission API and does not require a direct LuckPerms integration.

### Main configuration

| Setting | Default | Purpose |
| --- | ---: | --- |
| `enabled` | `true` | Enables reaction publishing |
| `cooldown-millis` | `2200` | Delay between reactions |
| `emotions.allow` | `[]` | Optional allow-list; empty enables the full catalog |
| `emotions.deny` | `[]` | Reactions excluded from the catalog |
| `broadcast.radius-blocks` | `64.0` | Maximum visibility radius |

The remaining `broadcast` and `ingress` values are server safety limits and should normally remain at their defaults.

## 🐛 Bug reports

Please open an [issue](https://github.com/TheWhish/Emotify/issues) and include:

- the Emotify, Minecraft, client loader, and server platform versions;
- the relevant client and server `latest.log` sections;
- clear reproduction steps;
- a screenshot or short recording for visual problems.

Remove private server addresses, access tokens, and personal information from logs before uploading them.

## Asset credits

The 162 bundled `8×8` emoji assets come from [Happy's Better Emojis](https://modrinth.com/resourcepack/happys-emojis) by [Happy_AlexRO](https://modrinth.com/user/Happy_AlexRO) and are licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).

The artwork is included without visual modification. Emotify provides its own catalog, stable IDs, categories, atlas coordinates, localization, animations, interface, and multiplayer system.

The attribution notice and complete license text are included in both release JARs and are available as [Happy-Better-Emojis-NOTICE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-NOTICE.txt) and [Happy-Better-Emojis-LICENSE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-LICENSE.txt).

## 📄 License

Emotify's source code and original project material are available under the [MIT License](LICENSE). The bundled emoji artwork remains subject to CC BY 4.0.

<div align="center">

<sub>Emotify is an independent project and is not affiliated with Mojang Studios, Microsoft, NeoForge, PaperMC, Purpur, Kotlin for Forge, Happy_AlexRO, or the Happy's Better Emojis project.</sub>

</div>
