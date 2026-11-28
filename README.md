<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

<div align="center">
  <img src="docs/images/app-icon.png" alt="HyperRear app icon" width="128" height="128" />
  <h1>HyperRear</h1>
  <p><strong>A music player for the rear screen of the Xiaomi 17 Pro series, for every app.</strong></p>
  <p>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="Official Telegram group" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="https://t.me/HyperMusicCoverEnhancedChannel"><img alt="Official Telegram channel" src="https://img.shields.io/badge/Telegram-Official_channel-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperRear is an extension of **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)**. It's still in early development and hasn't had a release yet.

Swipe down on the rear screen while music plays and you normally get Xiaomi's music card, which only shows up for apps on the system's list. HyperRear puts its own player there instead, for any app that plays music, and the cover moves the way it does in HyperMusicCover-Enhanced.

## Features

| | |
| --- | --- |
| 🎵 **Every player** | The card appears for any app that plays music |
| 🖼️ **Artwork** | The cover shrinks when you pause and turns over when the track changes, as in HyperMusicCover-Enhanced |
| 🎚️ **HyperOS controls** | The system's own animated buttons and glowing seek bar. Drag the bar to seek |
| 🎤 **Lyrics and visualizer** | Both come from HyperMusicCover-Enhanced. Tap the lyrics button to swap the cover for the lyrics |
| ⭕ **Camera rings** | A ring round the top camera shows the volume when you change it. With the lyrics open, the seek bar moves to a ring round the lower camera |
| 🌫️ **Artwork backdrop** | The current cover, blurred behind the card |

Still to come: a look for the rear always-on display.

## Requirements

- Xiaomi 17 Pro (tested) or 17 Pro Max (untested), on HyperOS 4
- An unlocked bootloader, root and LSPosed
- HyperMusicCover-Enhanced, signed with the same key

## Build and install

```sh
./gradlew assembleRelease
```

1. Install the APK, then open HyperMusicCover-Enhanced and go to **Extensions**.
2. Follow the HyperRear card: enable it in LSPosed, approve the **com.xiaomi.subscreencenter** scope, then let it restart the rear screen.
3. Play some music and swipe down on the rear screen.

The switches on HyperRear's page in **Extensions** turn the player, the volume ring and the progress ring on or off.

## Status

Alpha, tested on the Xiaomi 17 Pro. HyperRear hooks one specific version of the rear screen's app, so a system update can switch it off until HyperRear catches up. If that happens, you get the native card back.

## Credits

- [Lewewe](https://github.com/Lewewe): development

License: [AGPL-3.0](LICENSE)
