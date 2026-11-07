"""Step 4: check the written translations against the English files.

    python tools/translate/validate.py --lang ar      one language
    python tools/translate/validate.py                every values-<lang> folder that exists

Per language and file it checks: every English id has a translation, the XML is well formed, placeholders and
tokens match the English text (same count, any order), plural quantities are the ones the language needs, text is
not still English (above --leftover), no string is longer than 3 times the English (4 for Thai and Bengali, which
need more characters; Chinese, Japanese and Korean are far shorter, so only the opposite check is useful there),
Japanese has kana and Chinese has none, apostrophes and quotes are
escaped for aapt, and Persian/Arabic use the right letters. Prints a report; exits 1 when there are errors.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as c  # noqa: E402

LOOSE_QUANTITIES = ("zero", "one", "two")
NON_LATIN = {"fa", "ar", "ru", "hi", "uk", "bn", "th", "ja", "ko", "zh-rCN", "zh-rTW", "ur"}
BRAND = re.compile(r"uMessage|SMS|MMS|SIM|PIN|OTP|URL|Wi-?Fi|PDF|XML|GPS|Android|Google|HTML|ChatGPT", re.I)
MAX_RATIO = 3
MAX_RATIO_BY_LANG = {"th": 4, "bn": 4}  # character counts: these scripts spend more characters than Latin
MIN_RATIO_LANGS = {"zh-rCN": 0.2, "zh-rTW": 0.2, "ja": 0.2, "ko": 0.2}  # CJK is normally shorter; far shorter means a dropped text
MIN_LONG = 20
LATIN_SHARE = 0.6
KANA = re.compile("[぀-ヿ]")
MIN_SCRIPT_LETTERS = 10  # a Japanese text this long without kana is Chinese
UNESCAPED_QUOTE = re.compile(r"(?<!\\)['\"]")
WRONG_LETTERS = {
    "fa": ("ي", "ك"),  # Arabic yeh and kaf
    "ar": ("ی", "ک"),  # Persian yeh and kaf
}


def target_map(path, errors):
    """{id: raw} and {entry id: [quantities]} of a written file, or None when it is missing or not XML."""
    if not os.path.isfile(path):
        return None
    try:
        ET.parse(path)
    except ET.ParseError as error:
        errors.append("%s: not well-formed XML (%s)" % (os.path.basename(path), error))
        return None
    values, quantities = {}, {}
    for entry in c.parse_resources(path):
        base = "%s:%s" % (entry.file, entry.name)
        if entry.kind == "string":
            values[base] = entry.items[0][1]
        else:
            quantities[base] = [k for k, _ in entry.items]
            for key, raw in entry.items:
                values["%s:%s" % (base, key)] = raw
    return values, quantities


def letters(text):
    return [ch for ch in c.TOKEN_RE.sub("", BRAND.sub("", text)) if ch.isalpha()]


def still_english(unit, plain, lang):
    """True when [plain] looks untranslated: same as the English, or mostly Latin in a non-Latin language."""
    words = [w for w in re.split(r"\W+", BRAND.sub("", unit.prot.text)) if w.isalpha()]
    if len(words) < 2:
        return False
    if plain.strip() == unit.prot.text.strip():
        return True
    chars = letters(plain)
    if lang in NON_LATIN and len(chars) >= 4:
        return sum(ch.isascii() for ch in chars) / len(chars) > LATIN_SHARE
    return False


def check_unit(unit, entry, raw, lang, errors, leftovers):
    prot = c.protect(raw)
    where = unit.id
    if not prot.text.strip():
        errors.append("%s: empty" % where)
        return
    loose = entry.kind == "plurals" and unit.key in LOOSE_QUANTITIES
    want, got = sorted(unit.prot.tokens), sorted(prot.tokens)
    if loose:
        extra = list(got)
        for token in want:
            if token in extra:
                extra.remove(token)
        missing = [t for t in want if t not in got and not c.NUMBER_TOKEN.fullmatch(t)]
        ok = not extra and not missing
    else:
        ok = want == got
    if not ok:
        errors.append("%s: placeholders or tags differ (English %s, translation %s)" % (where, want, got))
    if "⟦" in raw or "⟧" in raw:
        errors.append("%s: leftover token mark" % where)
    if not prot.quoted and UNESCAPED_QUOTE.search(re.sub(r"<[^>]*>", "", raw)):
        errors.append("%s: apostrophe or quote without a backslash" % where)
    max_ratio = MAX_RATIO_BY_LANG.get(lang, MAX_RATIO)
    if len(prot.text) > max_ratio * len(unit.prot.text) and len(prot.text) > MIN_LONG:
        errors.append("%s: longer than %dx the English (%d against %d)"
                      % (where, max_ratio, len(prot.text), len(unit.prot.text)))
    check_script(where, prot.text, unit.prot.text, lang, errors)
    for wrong in WRONG_LETTERS.get(lang, ()):
        if wrong in prot.text:
            errors.append("%s: wrong letter U+%04X" % (where, ord(wrong)))
            break
    if unit.translate and still_english(unit, prot.text, lang):
        leftovers.append(where)


def check_script(where, text, english, lang, errors):
    """Japanese without kana is Chinese, Chinese with kana is Japanese, and a CJK text under a fifth of the length of
    a long English text lost words."""
    chars = letters(text)
    kana = bool(KANA.search(text))
    if lang == "ja" and len(chars) >= MIN_SCRIPT_LETTERS and not kana:
        errors.append("%s: Japanese text without kana (looks Chinese)" % where)
    if lang in ("zh-rCN", "zh-rTW") and kana:
        errors.append("%s: kana in Chinese text" % where)
    if lang in MIN_RATIO_LANGS and len(english) >= MIN_LONG and 0 < len(text) < MIN_RATIO_LANGS[lang] * len(english):
        errors.append("%s: far shorter than the English (%d against %d)" % (where, len(text), len(english)))


def check_quantities(entry_id, found, lang, errors):
    need = set(c.LANGS[lang]["quantities"])
    allowed = need | set(c.LANGS[lang]["accept"])
    if need - set(found):
        errors.append("%s: missing plural quantities %s" % (entry_id, sorted(need - set(found))))
    if set(found) - allowed:
        errors.append("%s: quantities %s are not used by this language" % (entry_id, sorted(set(found) - allowed)))


def validate_language(res_dir, lang, leftover=0.05):
    """(errors, warnings, stats) for the written values-<lang> folder."""
    errors, warnings, leftovers = [], [], []
    stats = {"files": 0, "units": 0}
    target = os.path.join(res_dir, c.folder_for(lang))
    seen_ids = set()
    for filename, entries, _ in c.all_units(res_dir, lang):
        file_errors = []
        parsed = target_map(os.path.join(target, filename), file_errors)
        errors += file_errors
        if parsed is None:
            if not file_errors:
                errors.append("%s: file is missing" % filename)
            continue
        values, quantities = parsed
        stats["files"] += 1
        for entry in entries:
            if entry.kind == "plurals":
                check_quantities("%s:%s" % (filename, entry.name), quantities.get("%s:%s" % (filename, entry.name), []),
                                 lang, errors)
            for unit in c.entry_units(entry, lang):
                seen_ids.add(unit.id)
                if unit.id not in values:
                    errors.append("%s: not translated" % unit.id)
                    continue
                stats["units"] += 1
                check_unit(unit, entry, values[unit.id], lang, errors, leftovers)
        extra_ok = set(c.LANGS[lang]["accept"])
        warnings += ["%s: not in the English file" % i for i in values
                     if i.startswith(filename + ":") and i not in seen_ids and i.rsplit(":", 1)[-1] not in extra_ok]
    translatable = max(stats["units"], 1)
    stats["leftover"] = len(leftovers)
    if len(leftovers) / translatable > leftover:
        errors.append("%d of %d units look untranslated (more than %d%%): %s"
                      % (len(leftovers), translatable, round(leftover * 100), ", ".join(leftovers[:8])))
    elif leftovers:
        warnings.append("%d units look untranslated: %s" % (len(leftovers), ", ".join(leftovers[:8])))
    return errors, warnings, stats


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", nargs="*", choices=c.ALL_LANGS)
    parser.add_argument("--res", default=c.DEFAULT_RES)
    parser.add_argument("--leftover", type=float, default=0.05, help="share of English-looking units to tolerate")
    parser.add_argument("--max-lines", type=int, default=30)
    args = parser.parse_args(argv)
    langs = args.lang or [l for l in c.ALL_LANGS if os.path.isdir(os.path.join(args.res, c.folder_for(l)))]
    if not langs:
        print("no values-<lang> folders found under %s" % args.res)
        return 1
    status = 0
    for lang in langs:
        errors, warnings, stats = validate_language(args.res, lang, args.leftover)
        print("%-7s %3d files, %4d units: %d errors, %d warnings" % (
            lang, stats["files"], stats["units"], len(errors), len(warnings)))
        for line in (["ERROR " + e for e in errors] + ["warn  " + w for w in warnings])[:args.max_lines]:
            print("   ", line)
        hidden = len(errors) + len(warnings) - args.max_lines
        if hidden > 0:
            print("    ... %d more" % hidden)
        status = status or (1 if errors else 0)
    return status


if __name__ == "__main__":
    sys.exit(main())
