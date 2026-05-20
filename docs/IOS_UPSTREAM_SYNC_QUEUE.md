# iOS Upstream Sync Queue

This document was rewritten from scratch after checking every iOS upstream commit after `1e2aa8d11d5cd1687e11c6b8735a999e7a8ed16f`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline: `1e2aa8d11d5cd1687e11c6b8735a999e7a8ed16f`
- Latest checked: `origin/develop` at `09951b41f6621d23bf3a0ee45cfa2a9cfdecea62`
- Checked on: 2026-05-20

## Current Queue

### 1. Dictionary pull-to-clear search reset

Status: pending Android sync.

Commit:

- `73a9e62` - pull to refresh.

iOS behavior to mirror:

- Dictionary search results now allow vertical bounce even inside the popup-backed results WebView.
- Pulling down past an 80-point threshold clears the current query when text is present.
- Pulling down with an empty query focuses the search field and shows the keyboard.
- While dragging, a small inset appears below the search bar with an arrow and text that changes from pull guidance to release guidance after the threshold.
- Releasing after the threshold clears text and focuses the search field; the reset indicator hides after deceleration.
- Dragging the results dismisses the keyboard.

Android notes:

- Android Dictionary tab currently has an explicit clear button in the search field but no pull-to-clear/show-keyboard gesture.
- Implement with Compose scroll state or nested scroll around the Dictionary results surface rather than adding iOS-specific WebView bounce semantics.
- New visible labels must be added to default English and Simplified Chinese string resources.

Validation:

- Search for a term in the Dictionary tab, pull the results down below threshold, and confirm the query clears, results reset, and keyboard focus returns.
- With an empty query, pull down below threshold and confirm the keyboard appears without changing results.
- Confirm ordinary vertical result scrolling and nested lookup popup interaction still work.

### 2. Popup action-button frame refresh fixes

Status: synced on Android 2026-05-20.

Commits:

- `958be70` - properly handle button scaling on iOS versions below 26.4.
- `edf8606` - refresh buttons when toggling harmonic frequency display.

iOS behavior to mirror:

- Popup native action-button rects account for WebKit zoom differences so buttons stay aligned to their placeholder slots under popup scaling.
- Button rect refresh uses the same rect-report path after scale changes, resize, redirect, restore, and slot state changes.
- Tapping harmonic frequency rows to swap between harmonic and normal frequency display triggers a native button-frame refresh.

Android sync notes:

- Android continues to report popup `buttonFrames` from JS and convert CSS pixels to Android pixels in `PopupActionButtonWebView`.
- Android popup scale uses CSS `zoom`; the existing `getBoundingClientRect()` path remains the shared source for scaled native action-button placement.
- Android `popup.js` now schedules visual-state button-frame sync after harmonic frequency row toggles so native action buttons refresh after the row height changes.

Validation:

- Open a lookup popup with audio/Anki buttons at popup scale 0.8, 1.0, and 1.5 and confirm buttons remain aligned to entry headers.
- Enable Harmonic Frequency, tap a harmonic frequency row to expand normal rows, and confirm audio/Anki buttons immediately move to the new header positions.
- Re-test redirect/back/forward, dictionary collapse toggles, popup scale changes, and slow horizontal drags/long presses on controls.

### 3. Vertical popup placement preference

Status: synced on Android 2026-05-20.

Commit:

- `e63d2c4` - prefer popup placement before reading direction in vertical.

iOS behavior to mirror:

- In vertical reading, popup placement prefers the side that has enough room for the popup before falling back to the reading-direction preference.
- If the right side can fit the popup's maximum width, iOS places the popup on the right even when the left side has more space.
- This avoids unnecessarily placing vertical popups opposite the selected text when the preferred side has enough room.

Android sync notes:

- Android `LookupPopupLayout.showOnRight()` now allows right placement when `spaceRight >= maxWidth` before falling back to the left/right space comparison.
- Re-check existing lower-screen recursive popup and continuous-mode popup placement expectations after this change.

Validation:

- In vertical reader mode, select text with enough right-side room but more left-side room and confirm the popup appears on the right.
- Confirm edge cases still clamp within screen bounds for narrow phones, lower-screen selections, recursive popups, and Dictionary tab popups.

### 4. Dictionary automatic updates

Status: pending Android sync.

Commit:

- `94d0c41` - dictionary auto updates.

iOS behavior to mirror:

- Dictionary settings show an Updates section when at least one installed dictionary is updatable.
- Updates include an `Update Automatically` toggle, default enabled.
- When automatic updates are enabled, users can choose Daily, Weekly, or Monthly; default is Weekly.
- Settings show the last successful dictionary update time, or Never when none has succeeded.
- On app activation, iOS checks whether automatic updates are enabled, whether updatable dictionaries exist, and whether the selected interval has elapsed.
- Automatic update sessions disallow expensive and constrained network access.
- Failed dictionaries do not cancel the whole update batch.
- Last update is recorded after at least one dictionary update check/import succeeds.
- Manual update still reports failures to the user.
- Updated dictionaries are imported into a temporary directory first, then moved into place so failed imports do not corrupt the installed copy.

Android notes:

- Android already supports manual update checks for installed updatable dictionaries, preserving enabled state, order, and collapsed-title migration on rename.
- Android currently lacks automatic update settings, last-update display, activation-triggered background checks, non-expensive network restriction, partial-failure behavior, and temp-then-move update import.
- Use Android platform/network APIs for network constraints rather than mechanically copying iOS `URLSessionConfiguration`.
- New visible settings labels and interval values must be localized in English and Simplified Chinese.

Validation:

- Install an updatable dictionary, open Dictionaries, and confirm the Updates section shows automatic update controls, interval choices, last update, and manual Update.
- With automatic updates enabled and an elapsed interval, foreground the app on an unmetered network and confirm updates run without blocking normal dictionary use.
- Simulate one dictionary update failure and one success; confirm the success is applied, failure is surfaced only for manual update, and last update advances only after a successful check/import.
- Confirm a failed import leaves the previous installed dictionary intact.

### 5. Reader image blur setting

Status: synced on Android 2026-05-20.

Commit:

- `f286108` - option to blur images.

iOS behavior to mirror:

- Appearance settings include a `Blur Images` toggle, default off.
- When enabled, large block images and SVG containers with embedded images load blurred in both paginated and continuous reader modes.
- The blur uses a strong CSS blur and clips to the image bounds.
- Tapping a blurred image prevents normal tap handling for that tap, removes the blur once, and leaves the image unblurred.
- Small inline images and gaiji images are not blurred.

Android sync notes:

- Android now stores `blurImages` in `ReaderSettings`, legacy SharedPreferences, and the reader DataStore.
- Appearance includes the localized `Blur Images` toggle in English and Simplified Chinese.
- Reader CSS now mirrors iOS blur styling for `img.block-img.blurred` and `svg.blurred`.
- Paginated and continuous reader setup scripts blur SVG containers with embedded images and large non-gaiji images, then remove the blur once on tap while preventing normal reader tap handling for that tap.

Validation:

- Enable Blur Images and open `testdata/test.epub` in paginated and continuous modes.
- Confirm cover/large illustration images and SVG image containers start blurred, while inline/gaiji images remain normal.
- Tap a blurred image and confirm it unblurs once without toggling reader chrome, selecting text, or turning the page.
- Disable Blur Images and confirm images load normally after reopening/reloading the reader.

## Covered Or No Android Action

- `a713c0c`: iOS keeps command-center previous/next cue controls wired even when skip controls are enabled, while the visible skip controls replace them as media controls. Android already keeps cue navigation available through reader chrome, Sasayaki sheet controls, and media-session previous/next commands while reader skip buttons are controlled separately. Re-test media controls when touching Sasayaki command behavior.
- `09951b4`: iOS build-number bump only. No Android sync.

## Full Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `958be70` | 2026-05-17 | Popup button rect scaling under zoom | Existing Android path retained |
| `73a9e62` | 2026-05-18 | Dictionary pull-to-clear/show-keyboard gesture | Pending |
| `edf8606` | 2026-05-18 | Refresh popup buttons after harmonic frequency toggle | Synced |
| `e63d2c4` | 2026-05-18 | Prefer fitting popup side before vertical reading direction | Synced |
| `94d0c41` | 2026-05-19 | Automatic dictionary updates | Pending |
| `a713c0c` | 2026-05-19 | Keep cue controls wired with skip controls | Covered; re-test |
| `f286108` | 2026-05-19 | Blur reader images until tapped | Synced |
| `09951b4` | 2026-05-19 | iOS build bump | No Android action |

## Suggested Implementation Order

1. Dictionary pull-to-clear: user-facing gesture/UI slice in the Dictionary tab.
2. Dictionary automatic updates: broader settings, repository, networking, and lifecycle slice; implement after deciding Android's network constraint mechanism.
