#!/usr/bin/env python3
"""Split multi-target sentence cards (Term = `悲劇,最中`) into per-word cards.

Rule (user): for each word in a comma-joined card, create a new card IF no card
for that word exists yet; otherwise that word is already covered. If every word
is already covered, the multi-word card is fully redundant -> drop it. New cards
inherit the ORIGINAL card's review schedule (not 'new').

Default = DRY RUN (no writes). Pass --apply to mutate a COPY, or
--apply --live to mutate the real collection (makes a backup first).

Run: uv run --with anki python split_multiword.py            # dry run
     uv run --with anki python split_multiword.py --apply     # on /tmp copy
     uv run --with anki python split_multiword.py --apply --live
"""
import os, re, sys, shutil
from collections import defaultdict
from anki.collection import Collection

COL_LIVE = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_split.anki2"
BACKUP_DIR = os.path.expanduser("~/.local/share/Anki2/User 1/backups")

WORD_FIELDS = ["Term", "Target Word", "Expression", "Word", "Vocab", "Kanji",
               "EntryFurigana", "Front", "見出し"]
SHARED = ["Sentence", "SentenceFurigana", "Cloze Before", "Cloze Inside",
          "Cloze After", "Context", "Notes", "Pitch Accent", "Image",
          "Sentence Audio", "Frequency", "Meaning", "Expanded Meaning",
          "Collapsed Meaning"]
SPLIT_COMMA = re.compile(r"[,、，]")
KANA_ONLY = re.compile(r"^[ぁ-んァ-ヶーゝゞ・]+$")


def clean_term(t):
    t = re.sub(r"<[^>]+>", "", t)
    t = re.sub(r"\[([^\]|]+)\|[^\]]+\]", r"\1", t)
    t = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", t)
    return t.strip()


_RUBY = re.compile(r"([^\[\]\s]+)\[([^\]]+)\]")
def reading_from_furi(furi_part):
    """ '癒[いや]やす' -> 'いややす';  '池[いけ]' -> 'いけ' (reading + loose kana)."""
    return _RUBY.sub(r"\2", furi_part).replace(" ", "").strip()


def head_word(fields, fvals):
    fmap = dict(zip(fields, fvals))
    for k in WORD_FIELDS:
        if fmap.get(k, "").strip():
            return clean_term(fmap[k])
    return clean_term(fvals[0]) if fvals else ""


def main():
    apply = "--apply" in sys.argv
    live = "--live" in sys.argv
    src = COL_LIVE if live else COL_COPY
    if not live:
        shutil.copy(COL_LIVE, COL_COPY)
    elif apply:
        os.makedirs(BACKUP_DIR, exist_ok=True)

    col = Collection(src)
    if apply and live:
        ok = col.create_backup(backup_folder=BACKUP_DIR, force=True, wait_for_completion=True)
        print("backup created:", ok, "->", BACKUP_DIR)

    nt_fields = {mm["id"]: [f["name"] for f in mm["flds"]] for mm in col.models.all()}
    nt_name = {mm["id"]: mm["name"] for mm in col.models.all()}

    # index every existing note by head-word -> (deck, notetype)
    index = defaultdict(list)
    for nid in col.find_notes(""):
        note = col.get_note(nid)
        hw = head_word(nt_fields[note.mid], note.fields)
        if hw:
            deck = col.decks.name(note.cards()[0].did) if note.cards() else "?"
            index[hw].append((nid, deck, nt_name[note.mid]))

    kino = col.models.by_name("jidoujisho Kinomoto")
    kfld = [f["name"] for f in kino["flds"]]
    comma_nids = []
    for nid in col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*'):
        t = dict(zip(kfld, col.get_note(nid).fields)).get("Term", "")
        if SPLIT_COMMA.search(t):
            comma_nids.append(nid)

    created_words = set()   # words we will create in THIS batch (batch dedupe)
    plan_split, plan_drop, plan_manual = [], [], []
    new_cards = 0

    for nid in comma_nids:
        note = col.get_note(nid)
        d = dict(zip(kfld, note.fields))
        terms = [p.strip() for p in SPLIT_COMMA.split(d.get("Term", "")) if p.strip()]
        reads = [p.strip() for p in SPLIT_COMMA.split(d.get("Reading", "")) if p.strip()]
        furis = [p.strip() for p in SPLIT_COMMA.split(d.get("Furigana", "")) if p.strip()]
        # recover missing/misaligned Reading from the per-part Furigana
        if len(reads) != len(terms) and len(furis) == len(terms):
            reads = [reading_from_furi(f) for f in furis]
        if not (len(terms) == len(reads) == len(furis) >= 2):
            plan_manual.append((nid, d.get("Term", ""),
                                f"misaligned T/R/F {len(terms)}/{len(reads)}/{len(furis)} → drop"))
            continue
        per = []
        for w, rd, fu in zip(terms, reads, furis):
            if not KANA_ONLY.match(rd):              # garbage reading (kanji left in) -> can't derive
                per.append((w, rd, fu, "skip", None))
            elif [x for x in index.get(w, []) if x[0] != nid]:
                per.append((w, rd, fu, "exists", [x for x in index.get(w, []) if x[0] != nid][0]))
            elif w in created_words:
                per.append((w, rd, fu, "batch", None))
            else:
                per.append((w, rd, fu, "create", None))
                created_words.add(w)
        to_create = [p for p in per if p[3] == "create"]
        usable = [p for p in per if p[3] in ("create", "exists", "batch")]
        if to_create:
            plan_split.append((nid, d.get("Term", ""), per))
            new_cards += len(to_create)
        elif usable:
            plan_drop.append((nid, d.get("Term", ""), per))   # all words already covered
        else:
            plan_manual.append((nid, d.get("Term", ""), "no derivable/usable word → drop"))

    # ---- report ----
    print(f"\n=== multi-word split plan  ({'LIVE APPLY' if (apply and live) else 'APPLY→copy' if apply else 'DRY RUN'}) ===")
    print(f"comma cards: {len(comma_nids)}")
    print(f"  → SPLIT  (create new + delete original): {len(plan_split)}  ({new_cards} new cards)")
    print(f"  → DROP   (all words already exist): {len(plan_drop)}")
    print(f"  → MANUAL (misaligned, skipped): {len(plan_manual)}")
    print("\nDROP (fully covered):")
    for nid, t, per in plan_drop:
        cov = "  ".join(f'{w}✓{e[1].split("::")[0]}/{e[2]}' for w, _, _, st, e in per if e)
        print(f"  {t:20s} {cov}")
    print("\nMANUAL (need a look):")
    for nid, t, why in plan_manual:
        print(f"  {t:24s} {why}")
    print("\nSPLIT sample (first 12):")
    for nid, t, per in plan_split[:12]:
        bits = []
        for w, rd, fu, st, e in per:
            bits.append(f'{w}=NEW' if st == "create" else
                        f'{w}=have({e[1].split("::")[0]}/{e[2]})' if st == "exists" else f'{w}=batch')
        print(f"  {t:20s} → {'  '.join(bits)}")

    if not apply:
        col.close()
        print("\n(dry run — nothing written. add --apply to enact on a copy.)")
        return

    # ---- enact ----
    sched_fields = ("type", "queue", "due", "ivl", "factor", "reps",
                    "lapses", "left", "odue", "odid")
    n_new = n_del = 0
    # plan_manual: can't derive a reading -> per user rule, drop them too
    for nid, t, why in plan_manual:
        col.remove_notes([nid])
        n_del += 1
    for nid, t, per in plan_split + plan_drop:
        src_note = col.get_note(nid)
        src_card = src_note.cards()[0]
        did = src_card.did
        sched = {k: getattr(src_card, k) for k in sched_fields}
        base = dict(zip(kfld, src_note.fields))
        audio_parts = [a for a in re.split(r"<br\s*/?>(?:\s*<br\s*/?>)?", base.get("Term Audio", "")) if a.strip()]
        align_audio = len(audio_parts) == len([p for p in per])
        for i, (w, rd, fu, st, e) in enumerate(per):
            if st != "create":
                continue
            nn = col.new_note(kino)
            vals = {f: "" for f in kfld}
            for f in SHARED:
                if f in vals:
                    vals[f] = base.get(f, "")
            vals["Term"], vals["Reading"], vals["Furigana"] = w, rd, fu
            # term audio: per-word if it aligns, else leave to first card
            if align_audio:
                vals["Term Audio"] = audio_parts[i]
            elif i == 0:
                vals["Term Audio"] = base.get("Term Audio", "")
            nn.fields = [vals[f] for f in kfld]
            nn.tags = list(src_note.tags) + ["ankialign::split-from-multiword"]
            col.add_note(nn, did)
            c = nn.cards()[0]
            for k, v in sched.items():
                setattr(c, k, v)
            col.update_card(c)
            n_new += 1
        col.remove_notes([nid])
        n_del += 1

    col.save()
    col.close()
    print(f"\nAPPLIED: created {n_new} cards, removed {n_del} multi-word notes "
          f"({'LIVE' if live else 'copy: '+COL_COPY})")


if __name__ == "__main__":
    main()
