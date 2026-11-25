# Changelog

What's new and fixed in Hoshi Reader Android.
Release notes before v1.3.0 are in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Added

- Boost or reduce playback volume for individual audiobooks.
- Sync your library and reading activity with Hoshi on iOS through Google Drive and download books on demand, even on devices without Google Play Services.
- Show reading progress in pages for the current chapter and the whole book.
- Use dedicated Chinese and Korean profiles with automatic profile switching by book language, pinyin grouping for Chinese, and lookup of inflected Korean words.
- App language switching now works on Android 8–12.
- Mark books as read from the bookshelf, or choose **Mark Unread** to reset a book’s reading position and statistics.
- Use kuma3 Anki to create cards.
- Enable **Exit Reader on First Back** to leave the reader with one Back action.
- Revisit or delete up to 100 recent dictionary searches, including lookups from other apps.
- Use a local word-audio database from its original location to save storage.

### Changed

- In continuous mode, you can scroll and select text across chapters, and the page stays still when you swipe past either end of the book.
- Drag a slider to set the font size or page margins, or use + and − to adjust them one step at a time.
- When you tap a word in the reader, the first dictionary definition appears while the rest of the entry loads, and tapping another word while the popup is open switches to it sooner.
- Replace Visual Novel Mode with Paragraph Mode, which centers text and offers animated text reveal, sentence limits, and tap-to-advance.
- Use an optional sentence replay button and see the current sentence and book cover in system media controls for audiobook and text-to-speech playback.
- Improve Japanese audiobook transcription with more accurate matching for repeated passages and resumable model downloads.
- Browse Statistics with previous and next buttons and see the current period update automatically as the date changes.
- Edit individual reading sessions in Statistics and count reading time on the correct day when a session crosses your daily reset.
- Reimport a dictionary ZIP to replace the installed dictionary with the same title and type.
- Preserve more of the original EPUB layout, including blank space and image margins.

### Fixed

- The previous and next buttons on Bluetooth headsets and car controls now follow your audiobook **Skip Action** setting.
- About now shows a dialog when a manual update check fails and no longer repeats old preview download errors when you return.
- With **Split into short clauses** enabled, text-to-speech also splits at ellipses and long dashes.
- When a word has multiple pronunciations, local audio lookup prefers recordings that match the dictionary reading and still honors the priority of sources that list only spellings.
- If Android stops audiobook playback in the background, you can resume in the open book with sentence underlining and automatic page turns working again.
- Transcription can start on M4B audiobooks with a damaged first audio frame, keeping subtitles in sync with the narration.
- Play local word audio for capitalized terms when the recording uses the lowercase spelling.
- Keep the line you tap in the Custom CSS editor visible when the keyboard opens.
- Open books with long chapters or large tables of contents faster and keep page turns responsive.
- Refresh progress and recent reading order promptly when you return to the bookshelf.
- Prevent duplicate shelf names from disrupting book assignments.
- Restore missing illustrations and covers in more EPUBs.
- Hide the status bar when viewing an illustration fullscreen.
- Recognize more Japanese inflections in lookups.

## [v1.6.0] - 2026-10-18

### Added

- A welcome screen on first launch sets everything up in one download, over any internet connection: 実用, 三省堂, JMdict, NHK pitch, JPDB frequency, KANJIDIC, the sentence-markup model, and the enrichment pack, so cards get example sentences right away. 実用 is placed above 三省堂. Hoshi asks for AnkiDroid access up front, creates the Hoshi note type when your collection does not have it, and maps the Mining card for `{definition}`, `{definition-source}`, `{pitch-graph}`, `{frequency-rank}`, `{example-sentences}`, and `{sentence-pitch}`, so you can make cards without fetching Anki or remapping fields. You can still switch to your own note type in Settings. Offline audio is optional and off by default; when on, the large audio file downloads in the background. Until then, word audio streams from the audio server.
- The add-card button doubles as "open card": once the word has a card it shows a check mark, and tapping it opens that card in AnkiDroid's browser (Back returns to Hoshi). The separate notes button is gone.
- The first time you open "Add to Anki", a tip explains that a long press on the add button adds the card instantly.
- Keep the latest 100 runtime errors in Diagnostics, including the operation,
  time, original error details and cause stack; retain them across restarts,
  and copy or export them with crash history.
  Show app/WebView information on the Diagnostics overview with separate entries
  for crash, error and process-exit records, opening full details from compact lists.
  Input validation, including unfinished regex replacement drafts, stays inline
  without entering runtime error history.
- Add Disable Button After Mining under Anki's Allow Duplicates: disable each term/format
  after successful mining until the popup is reopened.
- Read Japanese books aloud using installed Android TTS engines or Edge, Azure,
  Microsoft Translator, Google Cloud and Google Translate online sources, plus multiple named
  self-hosted OpenAI Compatible and VITS Simple API servers, with a separate
  TTS panel styled like Sasayaki, with compact playback controls and Voice,
  Replacements and Settings tabs. Source and Voice share a compact card with grouped,
  scrolling bottom selection panels. Voices retain their original names and show their
  online/offline requirement; installed third-party engines remain available. Source selection
  includes cloud credential configuration, with system TTS settings always available as
  a separate action above the Device engines group. Advanced settings label
  dictionary pronunciation settings as Word audio to distinguish them from narration,
  and give TTS its own speaking-person icon.
  Omit subtitles for Sasayaki and Book Cover Lock Screen. Add independent Hoshi
  playback speed from 0.5× to 3.0× alongside engine speech rate; it applies to narration
  and preview without re-synthesizing audio, shifting pitch, or resuming paused playback. Rate and pitch sliders
  keep their steps without dense tick marks or endpoint labels, and preview can be canceled or stopped from
  its theme-colored button fixed above the bottom system inset. Synthesis parameters appear
  directly in Voice without a separate heading or toggle; audio format is selected there
  rather than duplicated in Source configuration.
  Provider pause switches, audio format and sample rate use plain settings rows, while
  remaining parameter cards have tighter vertical padding; slider row heights stay unchanged.
  Configurable sources default to MP3, retaining saved WAV/MP3 selections.
  Each Source can optionally use separate narration and dialogue voices, remembered
  independently and selected from the existing voice picker. Paired Japanese quotes
  route dialogue across sentences and paragraphs; reading, replacement preview and
  Anki sentence audio share this behavior while keeping sentence navigation unchanged.
  Cloud credentials use a matching configuration sheet; connection testing and its
  success/error feedback stay visible in a fixed footer for cloud and self-hosted sources,
  without requiring another scroll after testing. Saved cloud API keys can be deleted
  immediately after confirmation, preserving other source settings. Saving or deleting
  another source's cloud credentials keeps the active voice list and preview unchanged.
  Azure regions use a compact dropdown of region codes with no default selection. Source errors
  stay beside the voice card instead of leaking into other tabs. Online voice lists remain
  available across panel openings and app restarts without automatic network refresh;
  the Voice picker provides a manual refresh action at the top for every source,
  retaining known voices during loading or refresh failure; slow self-hosted refreshes
  do not block reuse of the previous list.
  E-ink shows readable loading text instead of a solid black progress line.
  Add, edit and delete self-hosted servers from Source; saving a new server selects it
  immediately without starting playback, and each retains its model, voice,
  engine rate and WAV/MP3 format, with optional encrypted credentials and draft connection
  testing. Choose voices separately under Voice: VITS shows server-provided character names,
  while OpenAI Compatible can list manually configured voice IDs alongside voices discovered
  from the server. Changing available IDs keeps the chosen voice and playback intact.
  Editing another server or renaming the current one leaves playback running.
  OpenAI model IDs use one editable field with an integrated list button; returned
  models open a nearby menu for selection, including lists with a single result.
  TTS Settings offers a global Use Ruby readings switch, enabled by default; disabling it
  sends original base text to the engine while preserving custom replacements for reading,
  preview and Anki sentence audio.
  Read XHTML books containing self-closing script/style tags without losing their
  body text during TTS preparation.
  Book text is prepared by chapter when TTS is used, avoiding full-book TTS parsing
  when opening Reader. Edit shared reading behavior directly in either panel,
  preserving consecutive changes to different controls,
  with independent Japanese voice/rate controls, sentence navigation, background playback,
  and shared Reader highlighting, page following, lookup pause, and image holds.
  Control narration from lookup popups: replay the selected sentence once, toggle
  playback, or close the popup and continue from that sentence.
  Microsoft Translator needs no user API key, discovers regional Japanese voices,
  and refreshes its temporary authorization automatically.
  Linking an audiobook hides TTS and preserves Sasayaki's audiobook controls.
  Reopening or switching books after narration keeps playback idle until explicitly started.
  The shared Volume Keys Control Audiobook / TTS setting enables audiobook seeking and
  TTS sentence navigation after starting narration in the current book session; popup term
  navigation takes priority over narration, then page turns.
  Previous/next sentence navigation preserves paused playback instead of starting audio.
  Lookup pauses survive switching directly to another word without a brief playback
  restart, and closing the popup resumes correctly when the sentence ended during the pause.
  The Reader’s top-right playback button also controls TTS after manually starting narration
  in the current book session, like the bottom controls. It remains available while paused
  and shares the Show Audiobook / TTS Toggle preference with Sasayaki.
  Recognizes Samsung's Japanese voices and refreshes available voices when returning
  from system settings. Configure cloud credentials and test synthesis in the current
  panel. Adjust supported pitch, volume, Azure/Microsoft Translator speaking styles/roles/intensity and
  punctuation silence, cloud audio format/sample rate with compact dropdown rows
  anchored at the right-side control, Google device profiles, and
  extra playback pauses between sentences. Pause sliders use 10 ms increments, with
  a normal range of 0–1000 ms for provider silence and 0–2000 ms for extra sentence
  pauses. Changing extra
  sentence pause takes effect at the next boundary without stopping narration or
  regenerating cached audio. Independent setting edits preserve other saved preferences.
  MP3 sources retain MP3 for playback and Anki.
  Ready sentence audio plays without waiting for later network synthesis, including
  installed Android engines using separate narration/dialogue voices. Installed-engine
  voice lists are reused during playback and refreshed manually or when returning to the
  panel. Continuous playback keeps ongoing prefetch when moving to the next sentence. Configure 1–20
  upcoming sentences to preload in Voice (default 5); changes adjust
  buffering without interrupting narration or regenerating cached audio.
  Replayed sentence audio remains reusable while upcoming sentences are preloaded,
  including sentences with multiple audio fragments.
  While narration is paused, chapter navigation and returning to Reader keep the selected
  reading position instead of jumping back to the narrated sentence. TTS shares
  Sasayaki's image holds and chapter presentation, keeping images visible for the
  configured duration before continuing; manual sentence jumps skip intervening images.
- Customize TTS text replacements for one book or all books from text selection or
  Settings. Selected text has line breaks removed before filling Matching text, keeping
  the single-line field consistent with the actual rule. Replacement text accepts readings,
  other text and punctuation; leaving it empty
  deletes the matching text from speech. Fully deleted sentences are skipped during continuous
  playback without sending empty synthesis requests. Supported substitutions include
  dashes or ellipses replaced with commas/periods for engine-controlled pauses. Add an opt-in
  regular-expression switch to replacement creation/editing, with capture-group substitution,
  book/global scope and validation. Explicit literal entries and available ruby take priority
  over regex rules. Both modes show the original and actual TTS input before preview; rules
  created from Settings can be tested with editable sample text. The editor opens with
  its initial preview/validation content ready, avoiding an opening
  size jump, and retains the preview area while edits are recalculated. The compact editor
  groups the rule, scope and sentence preview, using matching body text sizes with distinct headings, labels and colors,
  a secondary Preview button and a primary Save action. Scope choices share the dialog
  background when unselected; preview recalculation keeps actions stable and queues Preview
  for the current draft instead of briefly disabling it. Empty results are clearly identified, remain saveable and disable Preview.
  Replacements affect only synthesized speech; original text, sentence navigation,
  highlighting and Anki sentence text stay unchanged.
  The selection menu offers a compact Replace action; its TTS replacement dialog
  defaults new entries to all books, with a theme-colored selected scope and stable centered labels,
  and a full-width theme-colored preview button, with
  a destructive Delete action aligned beside Cancel and Save when editing an entry.
  Replace also opens when a ruby-adjacent selection begins inside an annotation,
  using only selected body text and preserving native selection handles.
  Text-selection creation previews the full original sentence; both fields request
  Japanese keyboard input; the replacement field offers Done to dismiss the keyboard
  before saving.
  EPUB ruby remains the fallback reading.
  Image-backed characters with ruby are read without freezing or exhausting memory.
- Add the opt-in Anki `{sentence-audio}` handlebar for audiobook or TTS sentence audio;
  `{sasayaki-audio}` and existing field mappings keep their audiobook-only behavior.

- Add LINE Seed JP to the rounded recommended reader fonts, with Thin, Regular, Bold,
  and ExtraBold variants.

### Changed

- "Mine with options" lists the enrichment pack's example sentences for the word right away, even offline; Massif's sentences are added below them as soon as the search answers.
- Replace BIZ UDPGothic with BIZ UDGothic in the recommended reader fonts.

### Fixed

- The add-card button in the dictionary popup stays tappable. A tap that does not add a card, or a duplicate check that does not finish, no longer leaves the button dead, and a tap still adds a card when the click does not arrive. Tapping it also works when the card needs word audio that has not been played yet.
- A sentence picked in "Mine with options" now splits into the cloze fields around the word even when it spells the word differently from what you looked up (猫 for ねこ), instead of putting the whole sentence in Cloze Before.
- Keep full-width lookup popups independent of continuous reader padding in both
  vertical and horizontal writing.
- Restore continuous reading to the text line or column edge when reopening a
  book, keeping text and furigana visible without half-line clipping or position drift.
- Preserve specific error details and underlying causes across imports, settings,
  Reader, dictionaries, Anki, audio, TTS, Sasayaki, sync, backups, updates and
  statistics, alongside the localized operation or category description.
  Failed update checks retain each attempted URL, original response and
  rate-limit details instead of showing only the last source's error.
- Stop using a file-download proxy that redirects release API requests to its
  homepage; reject HTML responses before parsing update information as JSON.
- Keep Japanese names containing middle dots intact when scanning text for lookup.
- Clear the Dictionary tab's search when opening a book, preventing old word audio
  from replaying when the book's profile is activated or restored.
- Show saved settings before their controls appear, preventing switches and
  selections from briefly showing defaults when opening settings pages or panels.
- Keep dictionaries marked `Exclude` in Anki Advanced available for lookup;
  omit them from aggregate and first-definition fields while preserving explicit
  single-dictionary and selected-definition mappings.
- Apply the selected-glossary fallback only when the selected dictionary has no
  glossary, preserving empty glossary values.
- Keep the audio-source menu beside its speaker button and fully inside lookup
  popups when popup scale is above 1, including narrow popups.
- Restore the text-selection menu after handle drags in paginated paragraphs,
  with or without ruby, and keep it near visible text when WebView reports empty
  or offscreen menu coordinates. Keep Highlight and TTS Replace available after
  menu recovery on large-screen devices, and prevent repeated recovery from
  hiding the menu or a selection handle in reverse selections across paragraphs.
  Recover inside the selection away from its handles, including fragmented text
  starting immediately after ruby, and avoid pressing a handle again when
  recovering single-character vertical selections. Keep menus available on the
  first long press and after successive handle drags, without unexpectedly
  clearing the selected text. Keep recovered menus near the selection handles
  when the range includes a paragraph's final punctuation across columns.
- Create, recolor and remove highlights when native selections begin or end
  inside ruby annotations or at element boundaries, using only selected body text.
- Keep text selection from unexpectedly turning pages or chapters when the
  native selection menu disappears or is recreated.
- Reduce text-selection flicker in paginated reading by preventing selection
  autoscroll while dragging selection handles.
- Use Nakuru for Japanese audiobook transcription, with model and runtime downloads
  on first use for arm64 and 32-bit ARM devices. Update matched reading cues during
  transcription so reading and playback can continue without pausing transcription.
  Automatically remove retired ReazonSpeech models and inference libraries to free
  storage while keeping existing transcripts, matches and audiobook files.
  Remove the separate component license buttons from About.

## [v1.5.0] - 2026-10-15

### Added

- Add a Live tab for Japanese text streamed by hoshi-bridge from your own machines,
  for example over Tailscale: visual novels on a Steam Deck and subtitles playing in mpv
  on a PC. Add several hosts with an optional token under Settings > Live and test each
  one; the tab connects to all of them and follows the most recently active session, with
  a chip to switch. The current line sits above a transcript of the whole subtitle track
  that highlights and follows playback. Tap a transcript line to show it for lookup,
  long-press a timestamp to seek, and pause or resume mpv from the header; looking up a word pauses the video
  (can be turned off). Mining uses the line as `{sentence}`; video cards get the line's
  audio through the new `{sentence-audio}` and a short animated clip through `{image}`,
  titled with the episode time, while visual novel cards get a Deck screenshot (picked
  web images take precedence). Adding a card from any tab pops up a small badge with the
  word and a count of cards added this session. `{sentence-audio}` also resolves to the Sasayaki cue audio
  in Reader and is the new default for SentenceAudio fields.
- Fill Anki cards offline with the enrichment pack, so they are final the moment they are mined. Under Settings → Anki → Enrichment pack, set the pack URL (the server's `pack.json`) and tap Update; the pack downloads once, resumes if interrupted, and only downloads again when its content changes. Map `{definition}`, `{definition-source}`, `{pitch-graph}`, `{example-sentences}` and `{frequency-rank}` to fields (all listed under show all handlebars). A word missing from the pack keeps `{glossary-first}` as its definition.
- Write JRP sentence markup (furigana and pitch-accent colouring) into the mining sentence offline with the new `{sentence-pitch}` handlebar, so cards no longer wait for the nightly server pass. Update the enrichment pack once to download the sentence model (the Sudachi full dictionary, about 360 MB, plus accent data); it is only downloaded again when it changes. Without it, or if markup fails, `{sentence-pitch}` holds the plain sentence.

## [v1.4.0] - 2026-09-24

### Added

- Transcribe Japanese audiobooks on-device, with downloadable models, performance presets, pause/resume and subtitle matching while reading.
- Import multiline subtitles and export partial or complete matches as SRT.
- Configure lookup frequency sorting per profile.
- Preserve furigana in highlights; select an exact highlight to recolor or remove it.
- Choose global light/dark palettes, custom reading colors, interface accents and app-wide E-ink optimization.
- Archive deleted-book statistics, restore them on reimport and edit daily records.
- Browse calendar and all-time statistics with comparisons, averages and goal history.
- Use Anki tag placeholders such as `{document-title}` and `{expression}`.
- Long-press a lookup audio button to choose a recording for playback and Anki.
- Tap source text above Dictionary or shared lookup results while retaining sentence context for Anki.
- Choose Off, Dimmed, Toggle or Hidden furigana display.
- Show, blur or hide book covers, and optionally hide collapsed-shelf thumbnails.
- Add dictionary categories, category-aware Anki fields, Kanji lookup, stroke-order fonts and more complete pitch accents.
- Download Japanese reader fonts with family, variant and weight choices.
- Navigate popup terms with volume keys and open lookups through `hoshi://search?text=...` links.
- Explain when AnkiDroid can be used directly without AnkiConnect.

### Changed

- Keep display settings global across profiles and use consistent theme colors and clearer E-ink selections throughout the app.
- Simplify Statistics around daily goals, reading time, heatmaps and period charts; move its settings into the always-visible Stats tab.
- Clearing Dictionary search now opens the keyboard while preserving results and history.
- Use JapanesePod101, LanguagePod101 and Jisho as independently configurable default word-audio sources.
- Always export dictionary images to Anki as media files and translate the selected-glossary fallback label into Chinese.
- Make book search literal, show full sentence context, return up to 100 results and mark the selected result.
- Align reader line spacing more closely with iOS and include surrounding Japanese punctuation in Sasayaki highlights.
- Highlight the current audiobook chapter in Contents and apply Recent/Title sorting to Google Drive books.
- Use low-memory dictionary imports for automatic updates.

### Fixed

- Preserve Dictionary results and Back/Forward history when switching tabs, including
  after following a definition link and swiping back.
- Recognize existing AnkiDroid cards with equivalent Unicode spellings to prevent
  duplicate cards.
- Open matching AnkiDroid cards regardless of its previously selected deck, while
  honoring the duplicate-search setting.
- Report failed dictionary imports by filename and reason, then continue the batch.
- Show a localized Reader message and Close action for missing or unreadable books.
- Recover Reader after a crash while preserving reading position, highlights, and
  Sasayaki cues.
- Avoid transient errors during automatic Google Drive refresh and keep cached books
  visible. Manual refresh still reports failures.
- Count Korean consistently in reading progress, search, and Sasayaki matches; ignore
  furigana fallback text in native counts and search.
- Confirm before resetting Dictionary custom CSS.
- Keep text and images visible in books with unusual publisher formatting.
- Restore book files and covers from iOS backups despite filename variations.
- Match Sasayaki subtitles immediately after selecting an SRT, including around chapter
  boundaries and large text gaps, without search tuning or a separate action.
- Import EPUB and TTU books with long, non-Latin titles without truncation.
- Render inline Japanese character images at the right size and color, and exclude them
  from image navigation.
- Show EPUB fallback text when an inline character image fails to load.
- Open EPUBs that previously remained on the Reader loading screen.
- Keep large lookup popups on-screen so their borders and scrollable content remain
  reachable.
- Keep the Dictionary `Frequency` label on one line on compact screens.
- Keep pitch accents aligned and dictionary labels readable when lookup entries wrap.

## [v1.3.3] - 2026-08-13

### Added

- Center the current chapter when opening Contents or the Sasayaki chapter list.
- Enable or disable individual local audio sources while preserving their order.
- Import Ogg Opus audiobooks with metadata and chapters, and show audiobook duration before playback.
- Adjust the paginated/VN swipe threshold, including disabling swipe page turns.
- Create up to three Anki formats with independent icons, decks, fields, tags and duplicate states, with copy and confirmed-delete actions.
- Find existing Anki notes from per-format lookup buttons.
- Add precise cloze fields, numeric pitch graphs and advanced glossary mapping options.

### Changed

- Increase the default popup size to 500 × 500 dp and maximum configurable height to 1000 dp.
- Align Lapis, Kiku and Senren field presets with iOS.

### Fixed

- Import supported files with `#` or `?` in their names.
- Keep reduced-motion popup scrolling within the visible screen area.
- Restore Contents and fragment jumps for EPUBs with nested navigation documents.
- Prevent staggered multi-touch taps from turning pages.
- Support large book IDs in Google Drive sync and TTU backups.
- Keep lookup available on Sasayaki-highlighted VN text.

## [v1.3.2] - 2026-08-04

### Added

- Create a shelf while moving selected books.
- Add a reader image gallery and chapter-based progress in Reader and Statistics.
- Configure the daily statistics reset time and pause tracking while sheets or fullscreen images are open.
- Publish the current cover to the lock screen, a PNG file or supported iReader screen savers, with Fit, Fill and Stretch modes.

### Changed

- Expand Sasayaki delay to ±4 seconds and playback speed to 0.5–3×.
- Rename Go to as Contents, order its tabs as Chapters/Highlights/Gallery/Search, and remove overscroll deformation.

### Fixed

- Preserve progress and lookup offsets around numeric HTML entities.
- Refresh manual bookshelf sync in place and improve repeated cover scrolling.
- Remember Contents and Sasayaki tabs during the reader session.
- Keep VN lookups and Anki sentences complete across screen boundaries.
- Prevent exported Anki media from overwriting different files.
- Preserve dictionary order when merging inflected lookup results.
- Avoid incorrect statistics when Sasayaki jumps to the previous chapter with image holds enabled.

## [v1.3.1] - 2026-07-11

### Added

- Configure Reader's top safe area height.

### Changed

- Improve lookup ranking and normalization of iteration marks, full-width numbers and emphatic text.
- Raise daily goal limits to 200,000 characters and 12 hours.

### Fixed

- Keep Statistics visible after enabling it and refresh book covers when changing date ranges.
- Keep lookup highlights within the selected word near ruby.
- Prevent VN vertical text from jumping columns after ruby.
- Keep long-pressed volume keys paging or seeking instead of changing system volume.

## [v1.3.0] - 2026-07-01

### Added

- Add full-library Statistics with habit summaries, calendar ranges, per-book totals, daily/weekly goals and an Advanced visibility switch.

### Changed

- Open Go to on Chapters, followed by Highlights and Search, and focus the search field when selected.

### Fixed

- Prefer exact expression-and-reading local audio matches before falling back to reading-only or expression-only entries.
- Read Sasayaki M4B title, author, and cover metadata from MP4 atoms when Android's platform metadata reader returns empty.
- Improve VN reader media screens, first-highlight display, vertical layout, punctuation wrapping, and lookup and Sasayaki highlight alignment.
- Keep VN and continuous vertical reader content aligned to the configured vertical padding instead of the bottom overlap area.
- Prevent reader lookups from crashing on words that begin with supplementary-plane kanji such as 𠮟.
- Keep Sasayaki image hold active while viewing fullscreen Reader images, and avoid repeated holds once the continuous Reader target image is already visible.

## [v1.3.0-fork] - 2026-06-18

### Added

- Add a "Mine with options" button to each dictionary entry — next to the existing one-tap "Add to Anki" — that opens a sheet to adjust a card before adding it. Available wherever lookups appear: the Dictionary tab, the in-book reader, and process-text lookup. One-tap "Add to Anki" is unchanged.
- In that sheet, choose the card's Sentence from a list of Massif example sentences for the word, instead of the default in-book context line.
- In that sheet, choose the card's Picture: none, the book cover (the default in the reader), or an image from a Bing search of the word shown as a thumbnail grid. The choice fills the Picture field through a single `{image}` marker (replacing the separate book-cover and web-image markers). Map Picture to `{image}` under Settings → Anki.
