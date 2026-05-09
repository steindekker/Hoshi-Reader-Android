<div align="center">

# Hoshi Reader Android

![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![License](https://img.shields.io/badge/license-GPLv3-blue)
[![Download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/total?label=download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases)
[![Latest download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/latest/total?label=latest%20download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/latest)

[English](README.md) | **简体中文**

一款面向日语沉浸学习的轻量 EPUB 阅读器，支持 Yomitan 辞典。

本项目是 [Hoshi Reader](https://github.com/Manhhao/Hoshi-Reader) 的 Android 原生复刻版。

<table>
  <tr>
    <td><img src="docs/images/readme/bookshelf.jpg" alt="书架" width="100%"></td>
    <td><img src="docs/images/readme/reader-lookup-popup.jpg" alt="阅读器查词弹窗" width="100%"></td>
    <td><img src="docs/images/readme/reader-eink-lookup.jpg" alt="墨水屏查词" width="100%"></td>
    <td><img src="docs/images/readme/reader-dark-theme.jpg" alt="深色阅读器" width="100%"></td>
  </tr>
  <tr>
    <td><img src="docs/images/readme/sasayaki-audiobook.jpg" alt="Sasayaki 有声书" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-management.jpg" alt="辞典管理" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-search.jpg" alt="辞典搜索" width="100%"></td>
    <td><img src="docs/images/readme/anki-field-mapping.jpg" alt="Anki 字段设置" width="100%"></td>
  </tr>
</table>

</div>

## 功能

- 导入 EPUB，并在书架查看阅读进度。
- 阅读日文书籍，支持竖排或横排、分页或连续滚动、音量键翻页，以及主题、字体和间距。
- 导入和管理 Yomitan 词条、频率和音调辞典。
- 在辞典页搜索，或在阅读器中点击文本查词；支持变形还原、嵌套查询，以及在线或本地单词音频播放。
- 使用 AnkiDroid 制卡，支持 [Lapis](https://github.com/donkuri/lapis) 兼容字段和重复检查。
- 使用 Sasayaki 有声书跟读，支持当前句子高亮、自动翻页、播放控制和 Android 系统媒体控制。
- 为墨水屏设备提供墨水屏主题选项。

## 使用指南

App 交互指南可以参考这篇 Hoshi Reader iOS 使用文档：[Hoshi Reader 使用文档](https://my.feishu.cn/wiki/SXzUw9F6AiPw99kdzwac5Cv8n0f)。

日语学习方法可以参考这篇基于二语习得理论的指南：[基于二语习得理论的日语学习指南](https://my.feishu.cn/wiki/YeOSwsG7giLuQxkcDFscUXVZn2f)。

## 开发状态

核心功能已可使用。

当前功能开发节奏已经放缓，因此阅读数据统计、多设备同步、高亮等较大功能不会立刻更新。

已发布的用户可见变化见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。

## 功能请求

在 Hoshi Reader Android 完成与 iOS 版的功能和行为对齐之前，本仓库默认不接受新的功能请求。通用功能请求请先提交到 iOS 仓库。

例外是 Android 特有功能，或因 iOS 系统限制无法实现的功能，例如墨水屏主题、音量键翻页等。

## 鸣谢

Hoshi Reader Android 基于以下生态：

- [Hoshi Reader iOS](https://github.com/Manhhao/Hoshi-Reader)：参考实现。
- [hoshidicts](https://github.com/Manhhao/hoshidicts) 及 [hoshidicts-kotlin-bridge](https://github.com/Manhhao/hoshidicts-kotlin-bridge)：Yomitan 辞典支持。
- [Yomitan](https://github.com/yomidevs/yomitan)：辞典格式和查词体验参考。
- [AnkiDroid](https://github.com/ankidroid/Anki-Android)：Android 挖卡集成。
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid)：本地音频行为和 AnkiDroid 重复范围 / checksum 查询参考。
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader)：阅读器和统计体验灵感来源。

## 许可证

本项目基于 GNU General Public License v3.0 发布。详情见 [LICENSE](LICENSE)。
