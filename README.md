<div align="center">

# 😊 Emotify

**A small visual language for the moments when chat is one message too slow.**

[![Version 0.3.0](https://img.shields.io/badge/Version-0.3.0-5865F2?style=flat-square)](#installation)
[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-Client_%26_Server-EA6A47?style=flat-square)](https://neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Client_%26_Server-DBD0B4?style=flat-square)](https://fabricmc.net/)
[![Paper & Purpur](https://img.shields.io/badge/Paper_%26_Purpur-Server-4A90E2?style=flat-square)](https://papermc.io/)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square)](LICENSE)

</div>

Emotify adds animated pixel-art reactions above players: a fast, quiet alternative to another chat message or a full-body emote.

Version **0.3.0** adds full **Fabric** support and lets NeoForge and Fabric clients share reactions across NeoForge, Fabric, Paper, and Purpur servers.

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

| Component | Supported version |
| --- | --- |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.234+** |
| Fabric Loader | **0.19.3 or newer** |
| Fabric API | **0.116.15+1.21.1 or newer compatible release** |
| Fabric Language Kotlin | **1.13.12+kotlin.2.4.0 or newer compatible release** |
| Paper / Purpur | **1.21.1** |
| Java | **21** |
| Kotlin for Forge | **5.12.x** |

All supported platforms use the same Emotify protocol. NeoForge and Fabric clients can connect to either modded server platform or to Paper and Purpur servers running the plugin. Players without the client mod can still join normally, but they do not send or see reactions.

> [!IMPORTANT]
> Emotify uses separate NeoForge, Fabric, and Paper artifacts. Install only the artifact for the platform on which it runs. Additional Minecraft versions are not part of this release.

## 📦 Installation

| Platform | Artifact | Location and requirements |
| --- | --- | --- |
| NeoForge | `emotify-0.3.0.jar` | `mods`; requires Kotlin for Forge `5.12.x` |
| Fabric | `emotify-fabric-1.21.1-0.3.0.jar` | `mods`; requires Fabric API and Fabric Language Kotlin |
| Paper / Purpur | `emotify-paper-1.21-0.3.0.jar` | `plugins`; has no additional server dependencies |

Players on Paper or Purpur can use either the NeoForge or Fabric client mod. Do not install multiple Emotify platform artifacts in the same game or server instance.

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

The attribution notice and complete license text are included in all three release JARs and are available as [Happy-Better-Emojis-NOTICE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-NOTICE.txt) and [Happy-Better-Emojis-LICENSE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-LICENSE.txt).

## 📄 License

Emotify's source code and original project material are available under the [MIT License](LICENSE). The bundled emoji artwork remains subject to CC BY 4.0.

<div align="center">

<sub>Emotify is an independent project and is not affiliated with Mojang Studios, Microsoft, NeoForge, PaperMC, Purpur, Kotlin for Forge, Happy_AlexRO, or the Happy's Better Emojis project.</sub>

</div>
