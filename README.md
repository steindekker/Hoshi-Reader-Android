<div align="center">

# Hoshi Reader Android

![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![License](https://img.shields.io/badge/license-GPLv3-blue)
[![Download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/total?label=download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases)
[![Latest download](https://img.shields.io/github/downloads/HuangAntimony/Hoshi-Reader-Android/latest/total?label=latest%20download)](https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/latest)

**English** | [简体中文](README.zh-CN.md)

A lightweight Japanese EPUB reader built for immersion learning with Yomitan dictionary support.

This project is a native Android recreation of [Hoshi Reader](https://github.com/Manhhao/Hoshi-Reader).

<table>
  <tr>
    <td><img src="docs/images/readme/bookshelf.jpg" alt="Bookshelf" width="100%"></td>
    <td><img src="docs/images/readme/reader-lookup-popup.jpg" alt="Reader lookup popup" width="100%"></td>
    <td><img src="docs/images/readme/reader-eink-lookup.jpg" alt="E-ink lookup" width="100%"></td>
    <td><img src="docs/images/readme/reader-dark-theme.jpg" alt="Dark reader" width="100%"></td>
  </tr>
  <tr>
    <td><img src="docs/images/readme/sasayaki-audiobook.jpg" alt="Sasayaki audiobook" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-management.jpg" alt="Dictionary management" width="100%"></td>
    <td><img src="docs/images/readme/dictionary-search.jpg" alt="Dictionary search" width="100%"></td>
    <td><img src="docs/images/readme/anki-field-mapping.jpg" alt="Anki field mapping" width="100%"></td>
  </tr>
</table>

</div>

## Features

- Import EPUBs and track reading progress from the bookshelf.
- Read Japanese books with vertical or horizontal text, paginated or continuous scrolling, volume-key page turning, custom themes, fonts, and spacing.
- Import and organize Yomitan term, frequency, and pitch dictionaries.
- Search dictionaries or tap reader text to look it up with deinflection, nested lookups, and online or local word audio.
- Mine AnkiDroid cards with [Lapis](https://github.com/donkuri/lapis)-compatible fields and duplicate checks.
- Follow Sasayaki audiobooks with cue highlighting, automatic page turning, playback controls, and Android media controls.
- Use an e-ink theme option for e-reader devices.

## Development Status

Core functionality is available.

Feature development has slowed down, so reading statistics, multi-device sync, highlights, and similar larger additions will not be updated immediately.

See [docs/CHANGELOG.md](docs/CHANGELOG.md) for shipped user-visible changes.

## Feature Requests

Until Hoshi Reader Android reaches feature and behavior parity with the iOS app, new feature requests are not accepted here by default. Please submit general feature requests to the iOS repository first.

Exceptions are Android-specific features or features that cannot be implemented on iOS because of system limitations, such as e-ink themes or volume-key page turning.

## Attribution

Hoshi Reader Android builds on this ecosystem:

- [Hoshi Reader iOS](https://github.com/Manhhao/Hoshi-Reader) as the reference implementation.
- [hoshidicts](https://github.com/Manhhao/hoshidicts) and [hoshidicts-kotlin-bridge](https://github.com/Manhhao/hoshidicts-kotlin-bridge) for Yomitan dictionary support.
- [Yomitan](https://github.com/yomidevs/yomitan) for dictionary format and lookup inspiration.
- [AnkiDroid](https://github.com/ankidroid/Anki-Android) for Android card mining integration.
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid) for local audio behavior and AnkiDroid duplicate scope/checksum query references.
- [ッツ Ebook Reader](https://github.com/ttu-ttu/ebook-reader) for reader and statistics inspiration.

## License

Distributed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
