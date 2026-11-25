<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

<div align="center">
  <h1>HyperRear</h1>
  <p><strong>A better music card for the Xiaomi 17 Pro series' rear screen.</strong></p>
  <p>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="Official Telegram group" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperRear is an extension of **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** and is in early development. There is no release yet.

The rear screen's music card, which you swipe down for while music plays, is replaced with HyperRear's own player. Its cover moves with HyperMusicCover-Enhanced's animations.

## Features

| | |
| --- | --- |
| 🎵 **Every player** | The card shows for any app that plays music, not only the system's list |
| 🖼️ **Artwork** | The cover shrinks when paused and turns over on a new track, the HyperMusicCover-Enhanced way |
| 🎚️ **HyperOS controls** | The system's own animated buttons and glowing seek bar. Drag the bar to seek |
| 🌫️ **Artwork backdrop** | The playing track's cover, blurred behind the card |

Coming next: lyrics and the visualizer from HyperMusicCover-Enhanced, and a look for the rear always-on display.

## Requirements

- Xiaomi 17 Pro (tested) or 17 Pro Max (not tested yet), HyperOS 4
- Unlocked bootloader, root and LSPosed

## Build and install

```sh
./gradlew assembleRelease
```

1. Install the APK and enable **HyperRear** in LSPosed with **com.xiaomi.subscreencenter** in its scope.
2. Restart the rear screen's app (or reboot): `su -c killall com.xiaomi.subscreencenter`.
3. Play music and swipe down on the rear screen.

## Status

Pre-alpha, tested on the Xiaomi 17 Pro. It hooks one version of the rear screen's app, so a system update can turn it off until HyperRear is updated. The native card comes back when that happens.

## Credits

- [Lewewe](https://github.com/Lewewe): development

License: [AGPL-3.0](LICENSE)
