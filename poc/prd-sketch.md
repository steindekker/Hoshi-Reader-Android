# Anki Align

A simple tool to make my Anki collection consistent. After it has synced the latest database from AnkiWeb It will:

Enrichment:
1. Insert term audio where not there and replace jpod ones with higher quality sources: NHK -> Shinmeikai -> Forvo -> Jpod
2. Insert pitch graphs where there are none
3. Enrich the sentence field with JRP-markup
5. Properly set Cloze fields
4. Append example sentences (not build yet)

Dedupe:
1. dedupe cards while merging reps

Update card template:
1. Set the card template to the one in the repo

Default the tool will run a dry-run and show what and how many changes it will apply.
Using --apply it will update the data in place.

## Config
- AnkiWeb username (env)
- AnkiWeb password (env)
- Note Type name
- Field mapping
    - Term
    - Term Furigana
    - Sentence
    - Sentence Furigana
    - Term Audio
    - Definition
    - Pitch
    - Cloze Start
    - Cloze Inside
    - Cloze End

## Notes
A general cleanup has already been performed. So the modifications only need to happen for newly added cards.

## Agents
A poc has been made (see `poc/`). You can use this as inspiration, but nothing may be linked, or compiled against this. If you need data like dicts or anything you can copy it over.
