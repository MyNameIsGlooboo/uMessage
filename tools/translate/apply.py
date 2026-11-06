"""Step 3: write the translated batches into Android resource files.

    python tools/translate/apply.py --lang ar          one language
    python tools/translate/apply.py                    every language that has finished batches

Reads work/<lang>/done/batch_NN.json (the translator's answers), restores the tokens, escapes the text for XML and
Android, and writes app/src/main/res/values-<lang>/<same file name>. Persian replaces the files in values-fa.
A file with a missing or broken unit is not written (and an existing one is left alone) unless --allow-missing,
which writes the good entries and skips the rest. Run validate.py afterwards.

    python tools/translate/apply.py --merge --lang de ja     top-up: add only the entries values-<lang> lacks

--merge reads the answers of a top-up (work/<lang>/done/<tag>_NN.json, any json file in done/ works), keeps every
existing translation untouched and adds each missing entry to the same-named file of values-<lang>, placed after
the entry that precedes it in the English file. Entries the folder already has are never rewritten.
"""
import argparse
import re
import glob
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as c  # noqa: E402

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
LOOSE_QUANTITIES = ("zero", "one", "two")


def load_translations(work_dir, lang):
    """{id: text} from every done/batch_NN.json; each answer may be {"units": [...]}, a list, or an id map."""
    found = {}
    for path in sorted(glob.glob(os.path.join(work_dir, lang, "done", "*.json"))):
        data = c.read_json_lenient(path)
        if isinstance(data, dict) and "units" in data:
            data = data["units"]
        if isinstance(data, dict):
            data = [{"id": k, "tr": v} for k, v in data.items()]
        for item in data:
            if isinstance(item, dict) and isinstance(item.get("tr"), str) and "id" in item:
                found[item["id"]] = item["tr"]
    return found


def render_entry(entry, units, translations, problems):
    """The XML of one entry, or None after recording a problem."""
    raws = []
    for unit in units:
        if not unit.translate:
            raws.append((unit, entry_raw(entry, unit)))
            continue
        text = translations.get(unit.id)
        if text is None:
            problems.append("missing  %s" % unit.id)
            return None
        loose = entry.kind == "plurals" and unit.key in LOOSE_QUANTITIES
        try:
            raws.append((unit, c.restore(text, unit.prot, may_drop_numbers=loose)))
        except ValueError as error:
            problems.append("broken   %s: %s" % (unit.id, error))
            return None
    attrs = (" " + entry.attrs) if entry.attrs else ""
    if entry.kind == "string":
        return '    <string name="%s"%s>%s</string>\n' % (entry.name, attrs, raws[0][1])
    tag, inner = ("plurals", 'quantity="%s"') if entry.kind == "plurals" else ("string-array", None)
    lines = ['    <%s name="%s"%s>\n' % (tag, entry.name, attrs)]
    for unit, raw in raws:
        open_tag = "<item %s>" % (inner % unit.key) if inner else "<item>"
        lines.append("        %s%s</item>\n" % (open_tag, raw))
    lines.append("    </%s>\n" % tag)
    return "".join(lines)


def entry_raw(entry, unit):
    """The English raw text of a unit that needs no translation (separators, numbers)."""
    for key, raw in entry.items:
        if key == unit.key or entry.kind == "plurals":
            return raw
    return entry.items[0][1]


def render_file(entries, units_by_entry, translations, problems, allow_missing):
    body = []
    failed = False
    for entry, units in zip(entries, units_by_entry):
        xml = render_entry(entry, units, translations, problems)
        if xml is None:
            failed = True
        else:
            body.append(xml)
    if (failed and not allow_missing) or not body:
        return None
    tools = ' xmlns:tools="http://schemas.android.com/tools"' if any("tools:" in e.attrs for e in entries) else ""
    return "%s<resources%s>\n%s</resources>\n" % (HEADER, tools, "".join(body))


# The plural category "one" also covers 0 in these languages, and lint wants a number in such a text. The texts are
# written for the real singular, so each plurals tag carries the lint ignore.
IMPLIED_QUANTITY_LANGS = ("fa", "fr", "hi", "pt-rBR", "ru", "uk", "bn")


def ignore_implied_quantity(xml, lang):
    if lang not in IMPLIED_QUANTITY_LANGS or "<plurals" not in xml:
        return xml
    return re.sub(r'<plurals name="([^"]+)">', r'<plurals name="\1" tools:ignore="ImpliedQuantity">', xml)


def mark_implied_quantity(xml, lang):
    if lang not in IMPLIED_QUANTITY_LANGS or "<plurals" not in xml:
        return xml
    if "xmlns:tools" not in xml:
        xml = xml.replace("<resources>", '<resources xmlns:tools="http://schemas.android.com/tools">', 1)
    return ignore_implied_quantity(xml, lang)


def normalize_persian(xml):
    """Persian convention (tests/PersianSeparatorTest): never the middle dot, which reads as a Persian zero next to a
    number. The Persian comma separates the parts instead, the way the owner-reviewed translation did."""
    return xml.replace(" · ", "، ").replace('">·<', '">،<')


def apply_language(res_dir, work_dir, lang, allow_missing=False, dry_run=False, merge=False):
    translations = load_translations(work_dir, lang)
    target = os.path.join(res_dir, c.folder_for(lang))
    index = c.target_index(res_dir, lang) if merge else {}
    written, skipped, problems = [], [], []
    for filename, entries, _ in c.all_units(res_dir, lang):
        per_entry = [c.entry_units(e, lang) for e in entries]
        file_problems = []
        if merge:
            xml = merge_file(os.path.join(target, filename), entries, per_entry, translations, file_problems,
                             allow_missing, lang, index)
            problems += ["%s  %s" % (filename, p) for p in file_problems]
            if xml is None:
                continue
            if xml == "":
                skipped.append(filename)
                continue
            ET.fromstring(xml.encode("utf-8"))
            if not dry_run:
                c.write_text(os.path.join(target, filename), xml)
            written.append(filename)
            continue
        xml = render_file(entries, per_entry, translations, file_problems, allow_missing)
        problems += ["%s  %s" % (filename, p) for p in file_problems]
        if xml is None:
            skipped.append(filename)
            continue
        xml = mark_implied_quantity(xml, lang)
        if lang == "fa":
            xml = normalize_persian(xml)
        ET.fromstring(xml.encode("utf-8"))
        if not dry_run:
            c.write_text(os.path.join(target, filename), xml)
        written.append(filename)
    return written, skipped, problems


def block_spans(text):
    """{entry name: (start, end)} of every string, plurals and string-array block in [text]."""
    spans = {}
    for match in c._BLOCK.finditer(text):
        for group in ("sattrs", "pattrs", "aattrs"):
            if match.group(group) is not None:
                spans[c._split_attrs(match.group(group))[0]] = (match.start(), match.end())
    return spans


def insertion_point(text, index):
    """(offset, prefix) for adding a new line before [index]: the start of its line when only blanks precede it."""
    start = text.rfind("\n", 0, index) + 1
    return (start, "") if not text[start:index].strip() else (index, "\n")


def merge_text(existing, entries, fragments):
    """[existing] XML with each [fragments] entry (name -> new XML block) added in the order of [entries].

    A block the file already has (a plural that lacked a quantity) is replaced; every other block stays as it is.
    """
    spans = block_spans(existing)
    names = [e.name for e in entries]
    edits = []  # (start, end, text)
    anchor = None  # end offset of the entry before the current one, once there is one
    for position, name in enumerate(names):
        if name in spans:
            start, anchor = spans[name]
            if name in fragments:
                edits.append((start, anchor, fragments[name].strip()))
        elif name in fragments:
            if anchor is not None:
                where, text = anchor, "\n" + fragments[name].rstrip("\n")
            else:
                later = next((spans[n][0] for n in names[position + 1:] if n in spans), None)
                where, prefix = insertion_point(existing, existing.rindex("</resources>") if later is None else later)
                text = prefix + fragments[name]
            edits.append((where, where, text))
    # Last edit first, so offsets stay valid and several insertions at one offset keep their order.
    for _, start, end, text in sorted(((n, *e) for n, e in enumerate(edits)), key=lambda e: (e[1], e[2], e[0]),
                                      reverse=True):
        existing = existing[:start] + text + existing[end:]
    return existing


def merge_file(target, entries, per_entry, translations, problems, allow_missing, lang, index):
    """New text of the file at [target] with the missing entries added; None when nothing is missing, "" to skip."""
    todo = [(e, u) for e, u in zip(entries, per_entry) if c.entry_missing(e, lang, index)]
    if not todo:
        return None
    fragments, failed = {}, False
    for entry, units in todo:
        xml = render_entry(entry, units, translations, problems)
        if xml is None:
            failed = True
        else:
            fragments[entry.name] = ignore_implied_quantity(xml, lang)
    if (failed and not allow_missing) or not fragments:
        return ""
    if os.path.isfile(target):
        with open(target, encoding="utf-8") as handle:
            existing = handle.read()
    else:
        existing = HEADER + "<resources>\n</resources>\n"
    text = merge_text(existing, entries, fragments)
    if "tools:" in text and "xmlns:tools" not in text:
        text = re.sub(r"<resources\b", '<resources xmlns:tools="http://schemas.android.com/tools"', text, 1)
    return text


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", nargs="*", choices=c.ALL_LANGS)
    parser.add_argument("--res", default=c.DEFAULT_RES)
    parser.add_argument("--work", default=c.DEFAULT_WORK)
    parser.add_argument("--allow-missing", action="store_true", help="write the good entries of a partly done file")
    parser.add_argument("--dry-run", action="store_true", help="check only, write nothing")
    parser.add_argument("--merge", action="store_true",
                        help="top-up: add only the entries values-<lang> lacks and keep every existing translation")
    args = parser.parse_args(argv)
    langs = args.lang or [l for l in c.ALL_LANGS if glob.glob(os.path.join(args.work, l, "done", "*.json"))]
    if not langs:
        print("no finished batches found under %s" % args.work)
        return 1
    status = 0
    for lang in langs:
        written, skipped, problems = apply_language(
            args.res, args.work, lang, args.allow_missing, args.dry_run, args.merge)
        print("%-7s wrote %3d files, left %3d alone, %d problems" % (lang, len(written), len(skipped), len(problems)))
        for problem in problems[:40]:
            print("   ", problem)
        if len(problems) > 40:
            print("    ... %d more" % (len(problems) - 40))
        status = status or (1 if problems else 0)
    return status


if __name__ == "__main__":
    sys.exit(main())
