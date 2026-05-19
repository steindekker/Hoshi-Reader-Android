# Translating Hoshi Reader Android

Hoshi Reader Android uses standard Android string resources.

## Where translations live

- Default English text: `app/src/main/res/values/strings.xml`
- Simplified Chinese text: `app/src/main/res/values-zh-rCN/strings.xml`

To add another language, create a new Android resource directory such as
`values-ja`, `values-fr`, or `values-pt-rBR`, then add a `strings.xml` file
with translations for every translatable key from the default file.

## Contribution rules

- Keep every placeholder exactly compatible with English. For example,
  `%1$s`, `%2$d`, and `%3$.1f` must appear with the same number and type.
- Do not translate resource names, XML tags, `translatable="false"` entries,
  URLs, file extensions, Anki template placeholders, or protocol strings.
- Keep product and feature names consistent: Hoshi Reader, Anki, AnkiDroid,
  AnkiConnect, Sasayaki, JMdict, JMnedict, Jiten, Jitendex, and Google Drive.
- Use XML escaping where needed: `&amp;`, `&lt;`, `&gt;`, and `\'`.
- For longer text that contains many punctuation characters, prefer CDATA.
- Keep plurals structurally identical to the English resource, even if the
  target language uses the same wording for every quantity.

Run this before opening a PR:

```bash
./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest
```
