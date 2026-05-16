# iOS Upstream Sync Queue

This document was rewritten from scratch after checking every iOS upstream commit after `5e01ba4ba9e2fd458536d48efdecaa48c7906cc0`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline: `5e01ba4ba9e2fd458536d48efdecaa48c7906cc0`
- Latest checked: `origin/develop` at `bc531074dc02edd42c2b435f5b527b0f0589e59c`
- Checked on: 2026-05-16

## Current Queue

### 1. Popup typography and scaling

Status: queued

Commits:

- `f524178` - custom fonts in popup.
- `a384533` - popup scale/zoom setting.

iOS behavior to mirror:

- Imported and downloadable fonts can be used inside dictionary popup CSS via generated `@font-face` declarations.
- The CSS editor has a Font menu that inserts `font-family: "..." !important;` at the cursor.
- Popup WebViews accept a persisted `popupScale` setting, default `1.0`, slider range `0.8...1.5`, step `0.05`.
- Popup zoom updates live without rebuilding the popup content.

Android notes:

- Android already has reader font import and `https://hoshi.local/fonts/...` resource routing for reader content; reuse that boundary for popup fonts instead of creating a separate storage path.
- Android currently has Custom CSS editing but no font insertion helper and no popup-scale setting.

Validation:

- Import `testdata/KleeOne-SemiBold.ttf`, select it through popup CSS insertion, and confirm lookup popup text uses the font.
- Change popup scale while a reader lookup popup is open and confirm content scales without losing selection, child popup, redirect, action bar, or scroll state.

### 2. Popup action buttons and frame sync

Status: queued

Commits:

- `14cd6f2` - replace popup JS audio/mining buttons with native SwiftUI/UIKit buttons.
- `6f94682` - prevent horizontal popup scroll while holding buttons.
- `bc53107` - refresh button frames when dictionary details expand/collapse.

iOS behavior to mirror:

- Audio and Anki mining buttons are no longer ordinary HTML buttons inside the popup document.
- JS emits placeholder button frames; native controls are positioned over those slots.
- Button state still reflects audio error, duplicate check result, disabled mining state, and `allowDupes`.
- Autoplay invokes the same native audio path.
- Button frames refresh after resize, zoom, redirect/back/forward restore, and dictionary collapse/expand toggles.
- Holding or tapping the overlay controls must not horizontally scroll the popup content.

Android notes:

- Do not copy the iOS UIKit implementation. Implement with Android/Compose/WebView primitives while preserving the same user-visible control behavior.
- Existing popup JS still owns much of the button rendering path; keep the WebView bridge as the integration point.
- This slice should also re-check child popup selections and action-bar history, because button-frame refresh touches redirect/back/forward state.

Validation:

- Dictionary tab lookup and reader lookup both show audio/mining controls aligned to entry headers.
- Duplicate state, Anki add state, audio error state, autoplay, redirect history, collapsed dictionary toggles, and child popups all keep controls aligned.
- Slow horizontal drags/long presses on controls do not move popup content sideways.

### 3. Bookshelf title rename and metadata fallback

Status: queued

Commit:

- `96f44d3` - option to rename book titles; migrate optional metadata fields to non-optional on iOS.

iOS behavior to mirror:

- Long-pressing a book exposes `Rename`.
- Rename opens a text prompt seeded with the current display title.
- Empty or whitespace-only rename clears the custom title.
- Sorting by title uses the display title.
- Delete and Mark Read dialogs use the display title.
- EPUB imports fall back to the source file name when the EPUB title is missing or empty.

Android notes:

- Android already falls back to the unpacked root/file name in EPUB parsing, but `BookMetadata.title` and `folder` are still nullable for iOS sidecar compatibility.
- Prefer adding `renamedTitle` and a `displayTitle` model/helper instead of breaking existing sidecar reads.
- Keep old iOS/Android sidecars readable when `renamedTitle` is absent.

Validation:

- Rename a book, sort by title, open reader, view chapters/highlights/statistics/Sasayaki metadata, delete confirmation, Mark Read confirmation, and backup/restore.
- Clear the rename and confirm original title returns.
- Import an EPUB with missing title metadata and confirm the visible title falls back to the file name.

### 4. Dictionary import, recommendation list, and collapsed cleanup

Status: queued

Commits:

- `4cd688f` - add JMnedict to recommended dictionaries and switch JMdict to the no-proper-names package.
- `2f5d71a` - autodetect dictionary type during manual import.
- `b3312d9` - remove deleted dictionaries from collapsed dictionary config.

iOS behavior to mirror:

- Recommended download set is JMdict without proper names, JMnedict, and Jiten.
- Manual dictionary import no longer asks the user to choose Term/Frequency/Pitch first.
- Import result determines which type directories receive term, frequency, and pitch data.
- Deleting any dictionary removes its title from collapsed dictionary settings.
- Updating a renamed dictionary preserves collapsed state under the new title.

Android notes:

- Android currently offers JMdict, Jiten, and Jitendex individually; this differs from latest iOS and should be intentionally reconciled.
- Android manual import remains type-specific through the Dictionaries screen.
- `de.manhhao.hoshi.HoshiDicts.importDictionary` exposes an `ImportResult`, but `DictionaryNativeBridge` currently discards the counts. Use the bridge result instead of guessing type from filenames.
- Android already migrates collapsed titles during dictionary update rename; deletion still needs collapsed-setting cleanup.

Validation:

- Import a term, frequency, pitch, and mixed dictionary archive from one picker path and confirm each lands in the right tab.
- Delete a collapsed dictionary and confirm it disappears from Collapse Dictionaries -> Configure.
- Download recommended dictionaries and confirm the resulting titles, update eligibility, enabled state, ordering, and lookup query rebuild.

### 5. Highlight grouping for unlabeled chapters

Status: queued

Commit:

- `d5e966f` - group highlights whose chapter has no label into the previous labeled section.

iOS behavior to mirror:

- Highlight list sections use the nearest previous TOC label when a highlighted spine item has no chapter label.
- This avoids separate blank/untitled sections for front matter, split chapters, image pages, or other unlabeled spine entries.

Android notes:

- Android `ReaderHighlightSections` currently groups by exact spine index and uses `Untitled` when no label exists.
- Mirror the iOS fallback loop: while the target spine index is greater than zero and has no label, walk backward until a labeled section is found.

Validation:

- Use an EPUB with highlights in unlabeled spine items between labeled TOC chapters and confirm they group under the previous visible chapter label.
- Confirm jump and delete still target the original highlight positions.

## Covered Or No Android Action

- `746a7ac` / `5e3885a`: iOS adds and renames Sasayaki seek controls to skip controls. Android already has richer Sasayaki skip controls with cue/5s/10s/15s/30s actions shared by reader chrome, Sasayaki sheet controls, and Android media controls. Keep the existing validation entry in `docs/TODO.md`.
- `9626f84`: iOS removes direct index mutation when closing popups. Android popup stacks are already state-list driven rather than mutating SwiftUI binding indices directly. Re-test nested popup dismissals when touching popup controls.
- `53c6980`: iOS-specific SwiftUI reader button/glass-effect cleanup. Android reader chrome has its own Material implementation; no direct sync unless a user-visible mismatch is found.
- `130f6cf`: iOS reader background ignores safe area. Android does not share the same safe-area rendering model; handle any Android status/navigation background issue as a separate platform fix.
- `6b2c3e8`: iOS build-number bump only. No Android sync.

## Full Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `f524178` | 2026-05-14 | Custom fonts in popup | Queued |
| `a384533` | 2026-05-15 | Popup scale/zoom | Queued |
| `96f44d3` | 2026-05-15 | Rename book titles; metadata fallback | Queued |
| `746a7ac` | 2026-05-15 | Add Sasayaki seek controls | Covered |
| `53c6980` | 2026-05-15 | Simplify iOS reader buttons | No direct Android action |
| `9626f84` | 2026-05-15 | Avoid direct popup index access | Covered; re-test with popup work |
| `d5e966f` | 2026-05-15 | Group unlabeled highlight chapters | Queued |
| `4cd688f` | 2026-05-15 | Recommended dictionary changes | Queued |
| `130f6cf` | 2026-05-16 | Reader background safe area | No direct Android action |
| `2f5d71a` | 2026-05-16 | Autodetect dictionary type | Queued |
| `b3312d9` | 2026-05-16 | Clean collapsed config on delete | Queued |
| `14cd6f2` | 2026-05-16 | Native popup action buttons | Queued |
| `6f94682` | 2026-05-16 | Prevent popup horizontal scroll | Queued with popup buttons |
| `6b2c3e8` | 2026-05-16 | iOS build bump | No Android action |
| `5e3885a` | 2026-05-16 | Rename seek to skip | Covered |
| `bc53107` | 2026-05-16 | Refresh popup button frames on toggle | Queued with popup buttons |

## Suggested Implementation Order

1. Popup typography and scale.
2. Popup action buttons and frame sync.
3. Dictionary import/recommendation cleanup.
4. Bookshelf title rename.
5. Highlight grouping.

This order keeps the riskiest WebView/popup work together, then moves to dictionary storage/import behavior, then lower-risk model/UI slices.
