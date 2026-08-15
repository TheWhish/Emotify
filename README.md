<div align="center">

# 😊 Emotify

**A small visual language for the moments when chat is one message too slow.**

[![Minecraft 1.21.1, 1.21.8, 1.21.11 & 26.2](https://img.shields.io/badge/Minecraft-1.21.1_%7C_1.21.8_%7C_1.21.11_%7C_26.2-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-Client_%26_Server-EA6A47?style=flat-square)](https://neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Client_%26_Server-DBD0B4?style=flat-square)](https://fabricmc.net/)
[![Paper & Purpur](https://img.shields.io/badge/Paper_%26_Purpur-Server-4A90E2?style=flat-square)](https://papermc.io/)
[![Java 21 & 25](https://img.shields.io/badge/Java-21_%7C_25-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square)](LICENSE)

</div>

Emotify adds animated emotions above players: a fast, quiet alternative to another chat message or a full-body emote.

---

## ✨ Features

- **162 built-in emojis** with persistent favorites, localized search, and complete English and Russian translations.
- **Four signature presentations** — Elastic Pop, Ribbon Weave, Lantern Release, and Echo Bloom — selected evenly for varied but restrained animation.
- **World-aware rendering** that follows player poses, stays behind blocks, and respects first-person and visibility rules.
- **Movement-friendly picker** that does not take control away from the player while it is open.
- **Nine quick slots** assigned by drag and drop, activated with the top-row or Numpad `1–9` keys while the picker is open, and saved independently from favorites for both built-in and custom emojis.
- **Reduced Motion** mode with a static fade-only presentation.
- **Server-authoritative multiplayer** with cooldowns, permissions, visibility filtering, bounded payloads, and abuse protection.
- **Custom emojis shared in multiplayer** from local PNG, JPG, JPEG, and animated GIF files, with lossless decoded-frame transfer and connection-local caching.
- **In-picker custom file diagnostics** that keep invalid files visible as passive cards and explain the exact problem on hover.
- **Local saving of shared custom emojis** with Shift and right-click, readable filenames, duplicate-lineage protection, and no additional network transfer.
- **Versioned client and server configuration** with bounded backups, legacy migration, and protection against overwriting future schemas.

Emotify keeps its visual language close to Minecraft: readable pixel sprites, smooth motion, and no particle noise competing with gameplay.

## 🌐 Compatibility

| Component | Supported version |
| --- | --- |
| Minecraft | **1.21.1**, **1.21.8**, **1.21.11**, and **26.2** |
| NeoForge | **21.1.234+** on 1.21.1; **21.8.54+** on 1.21.8; **21.11.45+** on 1.21.11; **26.2.0.59+** on 26.2 |
| Fabric Loader | **0.19.3 or newer** |
| Fabric API | **0.116.15+1.21.1** on 1.21.1; **0.129.0+1.21.8** on 1.21.8; **0.141.6+1.21.11** on 1.21.11; **0.157.0+26.2** on 26.2, or newer compatible releases |
| Fabric Language Kotlin | **1.13.12+kotlin.2.4.0 or newer compatible release** |
| Paper / Purpur | **1.21.1**, **1.21.8**, **1.21.11**, and **26.2** |
| Java | **21** on 1.21.1–1.21.11; **25** on 26.2 |
| Kotlin for Forge | **5.12.0 or a newer compatible 5.x release** on 1.21.1 and 1.21.8; **6.3.0 or a newer compatible 6.x release** on 1.21.11 and 26.2 |

All supported platforms use the same Emotify protocol. NeoForge and Fabric clients can connect to either modded server platform or to Paper and Purpur servers running the plugin. Players without the client mod can still join normally, but they do not send or see emotions.

> [!IMPORTANT]
> Emotify uses separate NeoForge and Fabric artifacts for each Minecraft version and one shared Paper/Purpur artifact. Install only the artifact matching the platform and Minecraft version on which it runs. Other Minecraft versions are not part of this beta release.

## 📦 Installation

| Platform | Artifact | Location and requirements |
| --- | --- | --- |
| NeoForge 1.21.1 | `emotify-neoforge-1.21.1-0.7.0-beta.1.jar` | `mods`; requires Kotlin for Forge `5.12.0` or a newer compatible 5.x release |
| Fabric 1.21.1 | `emotify-fabric-1.21.1-0.7.0-beta.1.jar` | `mods`; requires Fabric API and Fabric Language Kotlin |
| NeoForge 1.21.8 | `emotify-neoforge-1.21.8-0.7.0-beta.1.jar` | `mods`; requires Kotlin for Forge `5.12.0` or a newer compatible 5.x release |
| Fabric 1.21.8 | `emotify-fabric-1.21.8-0.7.0-beta.1.jar` | `mods`; requires Fabric API and Fabric Language Kotlin |
| NeoForge 1.21.11 | `emotify-neoforge-1.21.11-0.7.0-beta.1.jar` | `mods`; requires Kotlin for Forge `6.3.0` or a newer compatible 6.x release |
| Fabric 1.21.11 | `emotify-fabric-1.21.11-0.7.0-beta.1.jar` | `mods`; requires Fabric API and Fabric Language Kotlin |
| NeoForge 26.2 | `emotify-neoforge-26.2-0.8.0-beta.1.jar` | `mods`; requires Kotlin for Forge `6.3.0` or a newer compatible 6.x release |
| Fabric 26.2 | `emotify-fabric-26.2-0.8.0-beta.1.jar` | `mods`; requires Fabric API and Fabric Language Kotlin |
| Paper / Purpur 1.21.1, 1.21.8, 1.21.11, 26.2 | `emotify-paper-1.21-0.8.0-beta.1.jar` | `plugins`; has no additional server dependencies |

Players on Paper or Purpur can use either the NeoForge or Fabric client mod. Do not install multiple Emotify platform artifacts in the same game or server instance.

### Client settings

Favorites and the nine quick slots are managed directly in the picker. Drag an emoji card onto a slot, press its number while the picker is open to use it, or right-click a filled slot to clear it. Built-in and custom assignments persist independently from favorites. After a complete stable custom-library refresh, a slot is cleared automatically if its local custom-emoji file no longer exists. Incomplete scans and decode or capacity failures preserve existing assignments. The shared client screen can hide all other players' emotions, hide only their custom emotions, manage an ignored-player list, enable Reduced Motion, and adjust the saved-emoji feedback and future emotion-sound volume. Your own emotions remain visible. Preferences, favorite order, and quick slots are stored in `config/emotify-client.toml` on NeoForge and `config/emotify-client.properties` on Fabric. The two loaders use different file formats but expose the same client behavior.

Custom emojis are loaded from direct PNG, JPG, JPEG, and GIF files in `<game directory>/emoji`. Static images support exact `8×8`, `16×16`, `32×32`, `64×64`, and `128×128` dimensions; GIF files support exact `8×8`, `16×16`, `32×32`, and `64×64` dimensions. GIF timing is normalized automatically: redundant frames are merged, high-frame-rate sources are sampled across their visible timeline, and content after the `3` second emotion lifecycle is clipped without accelerating the retained animation. The resulting asset remains bounded to 30 frames and 15 FPS without resizing the image or inventing frames for low-FPS sources. Source GIFs may contain up to 300 frames, and every image file is limited to `512 KiB`. The picker refreshes automatically after changes, and lossless transfer is prepared outside the render thread. Compatible Emotify peers receive content-addressed decoded pixels, frame durations, a bounded display name, and a stable origin ID; paths and original image files never leave the client.

Hold Shift and right-click the stable `0.9×0.95` block area above a player while their custom emotion is active to save it into your local `emoji` folder. The raised upper edge covers stacked multi-sprite animations, while the narrower width and raised lower edge leave a clear gap above the player model. The hit test uses the current vanilla block-interaction range and block occlusion, and does not require aiming at an animated sprite. It runs only on the click and sends no additional packet: the recipient reuses the already cached decoded asset. The transmitted display name becomes a safe readable filename; collisions use `Name (2)` without overwriting files. Static assets are written losslessly as PNG and animated assets as normalized looping GIFs. Both retain a stable origin marker, so re-encoding cannot turn a copied emotion into a new copyable lineage. Saving runs outside the render thread through a temporary file and atomic move. Only a vanilla pickup sound confirms success, following the client sound-volume slider.

## 🛡️ Server administration

Custom emoji sharing is enabled by default and can be disabled independently from built-in emotions. Server settings are stored in:

| Platform | Server configuration |
| --- | --- |
| NeoForge | `<world>/serverconfig/emotify-server.toml` |
| Fabric | `config/emotify-server.properties` |
| Paper / Purpur | `plugins/Emotify/config.yml` |

All server formats use configuration schema `1`. A valid versionless file is treated as legacy schema `0`, backed up, and migrated once without changing platform format. Legacy cooldown values from `2200` to `2999` milliseconds are raised to the current safe minimum of `3000` during that migration. Fabric and Paper/Purpur create a fixed `.v0.bak`; NeoForge uses its bounded numbered `ModConfigSpec` backup. A future schema is never rewritten by an older build.

NeoForge and Fabric apply their settings on server start. Paper and Purpur can apply them without restarting:

```text
/emotify reload
```

Fabric rejects invalid configuration at startup. NeoForge uses its standard `ModConfigSpec` behavior and may restore an invalid current-schema value to its safe default. If the file belongs to a future schema, NeoForge and Fabric disable Emotify for that server session while leaving the file untouched. Paper/Purpur rejects an invalid or future-schema reload without replacing the active policy; at initial startup an unsupported future schema prevents the plugin from enabling. A successful Paper/Purpur reload preserves connected sessions, active cooldowns, and abuse-protection state.

### Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `emotify.use` | Everyone | Send emotions |
| `emotify.receive` | Everyone | Receive emotions from other players |
| `emotify.admin.reload` | Operators | Reload the plugin configuration |

The plugin uses the standard Bukkit permission API and does not require a direct LuckPerms integration.

### Main configuration

| Setting | Default | Purpose |
| --- | ---: | --- |
| `enabled` | `true` | Enables emotion publishing |
| `custom-emojis.enabled` | `true` | Allows players to publish custom emojis |
| `custom-emojis.maximum-static-resolution` | `128` | Maximum accepted static custom-emoji resolution |
| `custom-emojis.maximum-animated-resolution` | `64` | Maximum accepted animated custom-emoji resolution |
| `cooldown-millis` | `3000` | Delay between emotions |
| `emotions.allow` | `[]` | Optional allow-list; empty enables the full catalog |
| `emotions.deny` | `[]` | Emojis excluded from the catalog |
| `broadcast.radius-blocks` | `64.0` | Maximum visibility radius |

The remaining `broadcast` and `ingress` values are server safety limits and should normally remain at their defaults.

Paper and Purpur use the hyphenated YAML paths shown above. NeoForge and Fabric expose the same shared settings with camel-case paths such as `customEmojis.enabled`, `cooldownMillis`, `broadcast.radiusBlocks`, and `ingress.globalBurstCapacity`. The Paper-only `ingress.maximum-queued-main-thread-tasks` option is not present on modded servers because their packet callbacks do not use the plugin's asynchronous main-thread dispatch queue.

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

The attribution notice and complete license text are included in every release JAR and are available as [Happy-Better-Emojis-NOTICE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-NOTICE.txt) and [Happy-Better-Emojis-LICENSE.txt](modules/emotify-catalog-builtin/src/main/resources/META-INF/licenses/emotify/Happy-Better-Emojis-LICENSE.txt).

## 📄 License

Emotify's source code and original project material are available under the [MIT License](LICENSE). The bundled emoji artwork remains subject to CC BY 4.0.

<div align="center">

<sub>Emotify is an independent project and is not affiliated with Mojang Studios, Microsoft, NeoForge, PaperMC, Purpur, Kotlin for Forge, Happy_AlexRO, or the Happy's Better Emojis project.</sub>

</div>
