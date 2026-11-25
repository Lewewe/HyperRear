<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

<div align="center">
  <h1>HyperRear</h1>
  <p><strong>为小米 17 Pro 系列背屏带来更好的音乐卡片。</strong></p>
  <p>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="官方 Telegram 群组" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="AGPL-3.0 许可证" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperRear 是 **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** 的扩展，目前处于早期开发阶段，暂无发布版本。

播放音乐时，背屏上下滑出现的音乐卡片会被替换为 HyperRear 自己的播放器。封面动画与 HyperMusicCover-Enhanced 一致。

## 功能

| | |
| --- | --- |
| 🎵 **所有播放器** | 任何播放音乐的应用都会显示卡片，不再局限于系统白名单 |
| 🖼️ **封面** | 暂停时缩小，切歌时翻转，与 HyperMusicCover-Enhanced 一致 |
| 🎚️ **HyperOS 控件** | 系统自带的动画按钮和发光进度条，拖动进度条即可跳转 |
| 🌫️ **封面背景** | 卡片背后是当前歌曲封面的模糊效果 |

即将加入：来自 HyperMusicCover-Enhanced 的歌词和音频可视化，以及背屏息屏显示的样式。

## 要求

- 小米 17 Pro（已测试）或 17 Pro Max（尚未测试），HyperOS 4
- 已解锁 Bootloader、Root 和 LSPosed

## 构建与安装

```sh
./gradlew assembleRelease
```

1. 安装 APK，在 LSPosed 中启用 **HyperRear**，作用域勾选 **com.xiaomi.subscreencenter**。
2. 重启背屏应用（或重启手机）：`su -c killall com.xiaomi.subscreencenter`。
3. 播放音乐，在背屏上下滑。

## 状态

预览版，已在小米 17 Pro 上测试。它适配的是背屏应用的某一个版本，系统更新后可能会失效，直到 HyperRear 更新为止。失效时会恢复系统原生卡片。

## 致谢

- [Lewewe](https://github.com/Lewewe)：开发

许可证：[AGPL-3.0](LICENSE)
