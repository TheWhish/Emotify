<div align="center">

# 😊 Emotify

**A small visual language for the moments when chat is one message too slow.**

[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.234-EA6A47?style=flat-square)](https://neoforged.net/)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square)](LICENSE)
[![Assets: CC BY 4.0](https://img.shields.io/badge/Assets-CC_BY_4.0-2E7D32?style=flat-square)](#asset-credits)

</div>

Emotify adds lightweight animated reactions above players. It complements chat and full-body emote mods with a faster, quieter way to express a mood without interrupting the game.

Version **0.1.0** establishes the complete NeoForge foundation: a polished emotion picker, a library of 162 pixel-art icons, persistent favorites, localized search, world-aware animation, and server-authoritative multiplayer.

---

## ✨ Expressive without becoming intrusive

- **162 built-in reactions** cover 130 faces and 32 animals in a consistent `8×8` pixel-art style.
- **Persistent favorites and localized search** keep the full library practical in both English and Russian.
- **World-aware rendering** follows standing, crouching, swimming, sleeping, riding, and elytra poses while remaining hidden behind blocks and outside the first-person view.
- **Clean visual timing** allows one complete reaction per player instead of stacking icons or replacing an animation halfway through.
- **Multiplayer-safe presentation** hides conflicting name tags, respects invisibility, and clears stale reactions when players or worlds change.

The result stays close to Minecraft's visual language: small readable sprites, restrained motion, and no particle noise competing with gameplay.

## 🌐 Multiplayer and compatibility

Emotify uses the same server-authoritative protocol in singleplayer and multiplayer. Clients request a registered emotion; the server validates and broadcasts it to compatible nearby players. Rate limits and bounded payloads keep the feature lightweight even on active servers.

For dedicated multiplayer, Emotify must be installed on the NeoForge server and on clients that want to send or see reactions. Players without Emotify can still join because its network channel is optional, but they do not participate in the emotion system.

| Requirement | Supported version |
| --- | --- |
| Minecraft | **1.21.1** |
| NeoForge | **21.1.234+** |
| Java | **21** |
| Kotlin for Forge | **5.12.x** |

> [!IMPORTANT]
> Paper/Purpur support, additional Minecraft versions and loaders, custom images, and user-created categories are future directions and are not included in **0.1.0**.

## 🐛 Bug reports

Please open an [issue](https://github.com/TheWhish/Emotify/issues) and include:

- your Minecraft, NeoForge, Kotlin for Forge, and Emotify versions;
- whether the problem happened in singleplayer or multiplayer;
- the relevant part of `latest.log`;
- a screenshot or short recording for visual or interface problems.

## Asset credits

The 162 bundled `8×8` emoji assets come from [Happy's Better Emojis](https://modrinth.com/resourcepack/happys-emojis) by [Happy_AlexRO](https://modrinth.com/user/Happy_AlexRO) and are licensed under [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).

The artwork is included without visual modification. Emotify provides its own catalog, stable IDs, categories, atlas coordinates, and English and Russian localization.

The attribution notice and complete license text are included in every JAR and are available as [Happy-Better-Emojis-NOTICE.txt](src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-NOTICE.txt) and [Happy-Better-Emojis-LICENSE.txt](src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-LICENSE.txt).

## 📄 License

Emotify's source code and original project material are available under the [MIT License](LICENSE). The bundled emoji artwork remains subject to CC BY 4.0.

<div align="center">

<sub>Emotify is an independent project and is not affiliated with Mojang Studios, Microsoft, NeoForge, Kotlin for Forge, Happy_AlexRO, or the Happy's Better Emojis project.</sub>

</div>
