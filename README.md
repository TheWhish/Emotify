<div align="center">

# 😊 Emotify

**Share what you feel — quickly, clearly, and without leaving Minecraft's style.**

[![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.234-EA6A47?style=flat-square)](https://neoforged.net/)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639?style=flat-square)](LICENSE)

</div>

Emotify brings quick visual reactions to Minecraft. Open a compact emotion menu, choose how you feel, and a small pixel-art icon appears above your character before gently floating upward and fading away.

The mod is designed as a lightweight social layer rather than a replacement for chat or full-body emote mods. Its goal is to make everyday multiplayer moments more expressive while keeping effects readable, restrained, and close to Minecraft's visual language.

> [!IMPORTANT]
> Emotify is currently in early development. The features below describe the planned **0.1.0** release and may be adjusted during playtesting before the first public build.

---

## ✨ Highlights

- 😊 **Six familiar emotions** cover happiness, sadness, anger, surprise, love, and confusion.
- 🖱️ **A compact emotion menu** keeps every reaction one click away without filling the screen with unnecessary controls.
- 🌤️ **Smooth overhead animations** appear above the player, rise gently, and disappear without particle overload.
- 🎨 **Original pixel-art icons** look consistent on every operating system and fit Minecraft's style.
- 🛡️ **Server-authoritative reactions** validate every selection and prevent clients from choosing their own range, duration, or recipients.
- ⏱️ **Cooldown and spam protection** keep busy servers readable without creating queues of overlapping emotions.
- 👤 **One active emotion per player** ensures that a new reaction cleanly replaces the previous one.
- 🖥️ **First- and third-person feedback** keeps your own reaction understandable even when your character is outside the camera view.
- ♿ **Accessible controls** are planned with keyboard navigation, English and Russian localization, and a reduced-motion option.
- 🌐 **Multiplayer-ready foundations** keep singleplayer and dedicated servers on the same networking path.

---

## 🔗 Compatibility

### Current target

The first release is being developed for:

- Minecraft **1.21.1**;
- NeoForge **21.1.234**;
- Java **21**;
- Kotlin for Forge **5.12.0**.

### Singleplayer and dedicated servers

In singleplayer, the client mod communicates with Minecraft's integrated server through the same protocol used in multiplayer.

On a dedicated NeoForge server, Emotify must be installed on the server and on each client that wants to send or display emotions. Clients without Emotify are planned to remain able to connect normally, but they will not see or use its reactions.

### Future platforms

The networking protocol is being designed with a future Paper/Purpur plugin bridge in mind. This will eventually allow a server plugin to communicate with the Emotify client mod without requiring NeoForge on the server.

Support for additional Minecraft versions and mod loaders is part of the long-term direction, but is not included in the initial 0.1.0 scope.

---

## 🐛 Found a bug?

Please open an [issue](https://github.com/TheWhish/Emotify/issues) and include:

- your Minecraft, NeoForge, Kotlin for Forge, and Emotify versions;
- whether it happened in singleplayer or on a dedicated server;
- what you were doing in the emotion menu or which emotion was displayed;
- the relevant part of `latest.log`;
- a screenshot or short recording for GUI, animation, visibility, or timing problems.

For multiplayer issues, please also mention whether Emotify was installed on both the server and the affected clients.

---

## 📄 License

Emotify is available under the [MIT License](LICENSE).

<div align="center">

<sub>Emotify is an independent project and is not affiliated with Mojang Studios, Microsoft, NeoForge, or Kotlin for Forge.</sub>

</div>
