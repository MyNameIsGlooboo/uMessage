"""Step 1: cut the English strings into translation batches.

    python tools/translate/extract.py                 all nineteen languages
    python tools/translate/extract.py --lang ar tr    only these
    python tools/translate/extract.py --missing-only --tag wave8 --lang de ja
        only the strings the existing values-<lang> folder does not have yet (top-up after new English strings)

Writes work/<lang>/batch_NN.json (units with protected placeholders, the glossary in the header) and, ready to
paste into ChatGPT, work/<lang>/prompt/batch_NN.txt. A top-up names its files <tag>_NN instead of batch_NN, so
finished answers are never overwritten and run_codex.py does not skip the new batches. For a new language without
a finished glossary it also writes work/<lang>/glossary_request.txt: send that first, review the answer and save
it as glossary/<lang>.json.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as c  # noqa: E402

BATCH_SIZE = 150
OVERFLOW = 1.2


def _read(name):
    with open(os.path.join(c.TOOLS_DIR, name), encoding="utf-8") as handle:
        return handle.read()


def entry_groups(res_dir, lang, missing_only=False):
    """[(filename, [unit-list per entry])] with units that need no translation left out.

    With [missing_only] an entry is kept only when the existing values-<lang> folder lacks it or part of it.
    """
    groups = []
    index = c.target_index(res_dir, lang) if missing_only else {}
    for filename, entries, _ in c.all_units(res_dir, lang):
        if missing_only:
            entries = [e for e in entries if c.entry_missing(e, lang, index)]
        per_entry = [[u for u in c.entry_units(e, lang) if u.translate] for e in entries]
        per_entry = [units for units in per_entry if units]
        if per_entry:
            groups.append((filename, per_entry))
    return groups


def make_batches(groups, size=BATCH_SIZE):
    """Whole files together where they fit; a larger file is split between entries (a plural never is)."""
    batches, current = [], []
    for _, per_entry in groups:
        total = sum(len(units) for units in per_entry)
        if current and len(current) + total > size * OVERFLOW:
            batches.append(current)
            current = []
        if total <= size * OVERFLOW:
            current += [u for units in per_entry for u in units]
            continue
        for units in per_entry:
            if current and len(current) + len(units) > size:
                batches.append(current)
                current = []
            current += units
    if current:
        batches.append(current)
    return batches


def unit_json(unit):
    item = {"id": unit.id, "en": unit.prot.text}
    if unit.note:
        item["note"] = unit.note
    return item


def glossary_text(glossary):
    return "\n".join("- %s = %s" % (en, tr) for en, tr in glossary.items() if tr) or "(none)"


def write_language(res_dir, work_dir, lang, size=BATCH_SIZE, missing_only=False, tag=None):
    tag = tag or ("topup" if missing_only else "batch")
    if missing_only and tag == "batch":
        raise ValueError("a top-up needs its own tag; 'batch' is the first run")
    out = os.path.join(work_dir, lang)
    for folder in (out, os.path.join(out, "prompt")):
        for stale in (os.listdir(folder) if os.path.isdir(folder) else []):
            if stale.startswith(tag + "_"):
                os.remove(os.path.join(folder, stale))
    batches = make_batches(entry_groups(res_dir, lang, missing_only), size)
    glossary, complete = c.load_glossary(lang)
    rules = c.language_rules(lang)
    template = _read("prompt_template.md")
    for number, units in enumerate(batches, 1):
        name = "%s_%02d" % (tag, number)
        payload = {
            "project": "uMessage", "lang": lang, "language": c.LANGS[lang]["name"],
            "batch": number, "batches": len(batches), "glossary": glossary,
            "units": [unit_json(u) for u in units],
        }
        c.write_text(os.path.join(out, name + ".json"), json.dumps(payload, ensure_ascii=False, indent=1) + "\n")
        prompt = template
        for key, value in {
            "language": c.LANGS[lang]["name"], "rules": rules, "glossary": glossary_text(glossary),
            "batch": str(number), "batches": str(len(batches)), "count": str(len(units)),
            "units": json.dumps(payload["units"], ensure_ascii=False, indent=1),
        }.items():
            prompt = prompt.replace("{{%s}}" % key, value)
        c.write_text(os.path.join(out, "prompt", name + ".txt"), prompt)
        os.makedirs(os.path.join(out, "done"), exist_ok=True)
    if not complete:
        terms = "\n".join("- %s%s" % (en, " | " + note if note and note.isascii() else "")
                          for en, _, note in c.glossary_rows())
        request = _read("glossary_prompt.md").replace("{{language}}", c.LANGS[lang]["name"])
        request = request.replace("{{rules}}", rules).replace("{{terms}}", terms)
        c.write_text(os.path.join(out, "glossary_request.txt"), request)
    return len(batches), sum(len(b) for b in batches), complete


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", nargs="*", choices=c.ALL_LANGS,
                        help="default: every language (with --missing-only: every language that has a values folder)")
    parser.add_argument("--res", default=c.DEFAULT_RES, help="res folder holding values/ (default: the app)")
    parser.add_argument("--work", default=c.DEFAULT_WORK)
    parser.add_argument("--size", type=int, default=BATCH_SIZE, help="units per batch")
    parser.add_argument("--missing-only", action="store_true",
                        help="only units the existing values-<lang> folder does not have (a top-up)")
    parser.add_argument("--tag", help="batch file prefix of a top-up (default topup); use a new one for every top-up")
    args = parser.parse_args(argv)
    langs = args.lang or [l for l in c.ALL_LANGS
                          if not args.missing_only or os.path.isdir(os.path.join(args.res, c.folder_for(l)))]
    for lang in langs:
        count, units, complete = write_language(args.res, args.work, lang, args.size, args.missing_only, args.tag)
        note = "" if complete else "  GLOSSARY MISSING: send work/%s/glossary_request.txt first" % lang
        print("%-7s %2d batches, %4d units%s" % (lang, count, units, note))


if __name__ == "__main__":
    main()
