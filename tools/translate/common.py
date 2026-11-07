"""Shared code for the uMessage translation tools (Python 3, standard library only).

Reads Android string resources as raw text, turns each translatable string, plural item and array item into a
"unit" with a stable id, and protects placeholders, tags and escapes with tokens so a translator cannot break them.
"""
import html
import json
import os
import re
from dataclasses import dataclass, field

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(TOOLS_DIR))
DEFAULT_RES = os.path.join(REPO, "app", "src", "main", "res")
DEFAULT_WORK = os.path.join(TOOLS_DIR, "work")
DOCS_FA = os.path.join(REPO, "docs", "translation-fa.md")

TOKEN_OPEN, TOKEN_CLOSE = "\u27e6", "\u27e7"
TOKEN_RE = re.compile("[\u27e6\u27e7]\\s*(\\d+)\\s*[\u27e6\u27e7]")

SKIP_FILES = {"donottranslate.xml"}
STRING_HINT = re.compile(r"<(string|plurals|string-array)\b")

# Language code -> name, resource folder suffix, CLDR plural quantities to write, extra quantities accepted.
LANGS = {
    "fa": dict(name="Persian (Farsi)", folder="fa", quantities=["one", "other"], accept=[]),
    "ar": dict(name="Arabic", folder="ar", quantities=["zero", "one", "two", "few", "many", "other"], accept=[]),
    "tr": dict(name="Turkish", folder="tr", quantities=["one", "other"], accept=[]),
    "es": dict(name="Spanish (neutral Latin American)", folder="es", quantities=["one", "other"], accept=["many"]),
    "ru": dict(name="Russian", folder="ru", quantities=["one", "few", "many", "other"], accept=[]),
    "hi": dict(name="Hindi", folder="hi", quantities=["one", "other"], accept=[]),
    "in": dict(name="Indonesian", folder="in", quantities=["other"], accept=["one"]),
    "pt-rBR": dict(name="Portuguese (Brazil)", folder="pt-rBR", quantities=["one", "other"], accept=["many"]),
    "fr": dict(name="French", folder="fr", quantities=["one", "other"], accept=["many"]),
    "de": dict(name="German", folder="de", quantities=["one", "other"], accept=[]),
    "it": dict(name="Italian", folder="it", quantities=["one", "other"], accept=["many"]),
    "zh-rCN": dict(name="Chinese (Simplified)", folder="zh-rCN", quantities=["other"], accept=["one"]),
    "zh-rTW": dict(name="Chinese (Traditional, Taiwan)", folder="zh-rTW", quantities=["other"], accept=["one"]),
    "ur": dict(name="Urdu", folder="ur", quantities=["one", "other"], accept=[]),
    "ja": dict(name="Japanese", folder="ja", quantities=["other"], accept=["one"]),
    "ko": dict(name="Korean", folder="ko", quantities=["other"], accept=["one"]),
    "vi": dict(name="Vietnamese", folder="vi", quantities=["other"], accept=["one"]),
    "th": dict(name="Thai", folder="th", quantities=["other"], accept=["one"]),
    "pl": dict(name="Polish", folder="pl", quantities=["one", "few", "many", "other"], accept=[]),
    "uk": dict(name="Ukrainian", folder="uk", quantities=["one", "few", "many", "other"], accept=[]),
    "bn": dict(name="Bengali", folder="bn", quantities=["one", "other"], accept=[]),
}
NEW_LANGS = ["ar", "tr", "es", "ru", "hi", "in", "pt-rBR", "fr"]
MORE_LANGS = ["de", "it", "zh-rCN", "ja", "ko", "vi", "th", "pl", "uk", "bn", "zh-rTW", "ur"]
ALL_LANGS = ["fa"] + NEW_LANGS + MORE_LANGS
# Scripts written without spaces between words (validate.py and the prompts treat them differently).
SPACELESS = {"zh-rCN", "zh-rTW", "ja", "th"}

QUANTITY_HINT = {
    "fa": {"one": "0 and 1", "other": "all other numbers"},
    "ar": {"zero": "0", "one": "1", "two": "2", "few": "3-10, 103-110", "many": "11-99, 111-199",
           "other": "100-102, 200-202 and fractions"},
    "tr": {"one": "1", "other": "all other numbers"},
    "es": {"one": "1", "other": "all other numbers"},
    "ru": {"one": "1, 21, 31, 101", "few": "2-4, 22-24, 102-104", "many": "0, 5-20, 25-30, 105-120",
           "other": "fractions"},
    "hi": {"one": "0 and 1", "other": "all other numbers"},
    "in": {"other": "every number"},
    "pt-rBR": {"one": "0 and 1", "other": "all other numbers"},
    "fr": {"one": "0 and 1", "other": "all other numbers"},
    "de": {"one": "1", "other": "all other numbers"},
    "it": {"one": "1", "other": "all other numbers"},
    "pl": {"one": "1", "few": "2-4, 22-24, 102-104", "many": "0, 5-21, 25-31, 105-121",
           "other": "fractions"},
    "uk": {"one": "1, 21, 31, 101", "few": "2-4, 22-24, 102-104", "many": "0, 5-20, 25-30, 105-120",
           "other": "fractions"},
    "bn": {"one": "0 and 1", "other": "all other numbers"},
    "ur": {"one": "1", "other": "all other numbers"},
}

# Files that users see first are translated first.
FILE_PRIORITY = [
    r"strings_onboarding|strings_onb_",
    r"strings_spam|strings_blocked|strings_r3_blocked|strings_review2_blocked|strings_notif_block|"
    r"strings_region_block|strings_rules|strings_allow_similar",
    r"strings_thread|strings_convx",
    r"strings_settings",
]


def folder_for(lang):
    return "values-" + LANGS[lang]["folder"]


def sort_key(filename):
    for rank, pattern in enumerate(FILE_PRIORITY):
        if re.match(pattern, filename):
            return (rank, filename)
    return (len(FILE_PRIORITY), filename)


@dataclass
class Entry:
    file: str
    kind: str  # string | plurals | array
    name: str
    attrs: str  # raw attribute text without name and translatable
    items: list  # [(key, raw_inner)]; key is "" for a string, a quantity, or an array index
    note: str = ""
    translatable: bool = True


_COMMENT = r"<!--(?P<comment>.*?)-->"
_STRING = r"<string\b(?P<sattrs>[^>]*?)>(?P<sbody>.*?)</string>"
_PLURALS = r"<plurals\b(?P<pattrs>[^>]*?)>(?P<pbody>.*?)</plurals>"
_ARRAY = r"<string-array\b(?P<aattrs>[^>]*?)>(?P<abody>.*?)</string-array>"
_BLOCK = re.compile("|".join([_COMMENT, _STRING, _PLURALS, _ARRAY]), re.S)
_ITEM = re.compile(r"<item\b(?P<iattrs>[^>]*?)>(?P<ibody>.*?)</item>", re.S)
_ATTR = re.compile(r'([\w:.-]+)\s*=\s*"([^"]*)"')


def _split_attrs(text):
    found = dict(_ATTR.findall(text))
    name = found.pop("name", "")
    translatable = found.pop("translatable", "true") != "false"
    rest = " ".join('%s="%s"' % kv for kv in found.items())
    return name, translatable, rest


def parse_resources(path):
    """All string-like entries of one resources file in order, with the comment directly above each as its note."""
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    filename = os.path.basename(path)
    entries = []
    note = ""
    for match in _BLOCK.finditer(text):
        if match.group("comment") is not None:
            note = " ".join(match.group("comment").split())
            continue
        if match.group("sattrs") is not None:
            name, translatable, attrs = _split_attrs(match.group("sattrs"))
            entry = Entry(filename, "string", name, attrs, [("", match.group("sbody"))], note, translatable)
        elif match.group("pattrs") is not None:
            name, translatable, attrs = _split_attrs(match.group("pattrs"))
            items = [(dict(_ATTR.findall(i.group("iattrs"))).get("quantity", ""), i.group("ibody"))
                     for i in _ITEM.finditer(match.group("pbody"))]
            entry = Entry(filename, "plurals", name, attrs, items, note, translatable)
        else:
            name, translatable, attrs = _split_attrs(match.group("aattrs"))
            items = [(str(n), i.group("ibody")) for n, i in enumerate(_ITEM.finditer(match.group("abody")))]
            entry = Entry(filename, "array", name, attrs, items, note, translatable)
        entries.append(entry)
        note = ""
    return entries


def resource_files(res_dir, folder="values"):
    base = os.path.join(res_dir, folder)
    if not os.path.isdir(base):
        return []
    names = [n for n in os.listdir(base) if n.endswith(".xml") and n not in SKIP_FILES]
    return sorted(names, key=sort_key)


# ---------- protection ----------

_PROTECT = re.compile(
    r"(?P<uni>\\u[0-9a-fA-F]{4})"
    r"|(?P<esc>\\[nt\\])"
    r"|(?P<plain>\\['\"@?])"
    r"|(?P<fmt>%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sSdfxXcb%n])"
    r"|(?P<ent>&lt;/?[A-Za-z][^&]*?&gt;)"
    r"|(?P<tag><[^>]+>)"
    r"|(?P<brace>\{[^{}\s]*\})"
)


@dataclass
class Protected:
    text: str
    tokens: list = field(default_factory=list)
    quoted: bool = False


def protect(raw):
    """Plain text for the translator with tokens for everything that must survive unchanged."""
    quoted = False
    body = raw
    stripped = raw.strip()
    if len(stripped) >= 2 and stripped.startswith('"') and stripped.endswith('"'):
        quoted, body = True, stripped[1:-1]
    tokens = []

    def swap(match):
        if match.group("plain"):
            return match.group("plain")[1]
        tokens.append(match.group(0))
        return "%s%d%s" % (TOKEN_OPEN, len(tokens), TOKEN_CLOSE)

    text = html.unescape(_PROTECT.sub(swap, body))
    return Protected(text if quoted else " ".join(text.split()), tokens, quoted)


NUMBER_TOKEN = re.compile(r"%(?:\d+\$)?[-#+0,(]*\d*[dfx]")


def token_numbers(text):
    return [int(n) for n in TOKEN_RE.findall(text)]


def restore(translated, source, may_drop_numbers=False):
    """Raw XML inner text from a translation of [source] (a Protected). Raises ValueError on a broken token set.

    [may_drop_numbers] lets a plural form such as "one" say "a message" without the number placeholder.
    """
    numbers = sorted(token_numbers(translated))
    wanted = list(range(1, len(source.tokens) + 1))
    if may_drop_numbers:
        droppable = {n for n in wanted if NUMBER_TOKEN.fullmatch(source.tokens[n - 1])}
        ok = (len(set(numbers)) == len(numbers) and set(numbers) <= set(wanted)
              and set(wanted) - set(numbers) <= droppable)
    else:
        ok = numbers == wanted
    if not ok:
        raise ValueError("tokens %s do not match the English %s" % (numbers, wanted))
    text = TOKEN_RE.sub(lambda m: "\x00%s\x00" % m.group(1),
                  translated if source.quoted else translated.strip())
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"').replace("\r\n", "\n").replace("\n", "\\n")
    if text[:1] in ("@", "?"):
        text = "\\" + text
    text = re.sub("\x00(\\d+)\x00", lambda m: source.tokens[int(m.group(1)) - 1], text)
    return '"%s"' % text if source.quoted else text


def has_letters(prot):
    return any(ch.isalpha() for ch in TOKEN_RE.sub("", prot.text))


# ---------- units ----------

@dataclass
class Unit:
    id: str
    file: str
    entry: Entry
    key: str  # quantity or index for the target; "" for a string
    prot: Protected
    note: str
    translate: bool


def _quantity_note(lang, quantity):
    if LANGS[lang]["quantities"] == ["other"]:
        return "%s has one plural form: write the text for any number (keep the number placeholder)." % (
            LANGS[lang]["name"].split(" (")[0])
    return "Plural form '%s' of %s: used for %s. Keep the number placeholder." % (
        quantity, LANGS[lang]["name"].split(" (")[0], QUANTITY_HINT[lang].get(quantity, ""))


def entry_units(entry, lang):
    """Units of [entry] as the target language needs them (plural quantities follow the language)."""
    if entry.kind == "string":
        sources = [("", entry.items[0][1], "")]
    elif entry.kind == "array":
        sources = [(k, raw, "") for k, raw in entry.items]
    else:
        by_q = dict(entry.items)
        other = by_q.get("other") or (entry.items[-1][1] if entry.items else "")
        sources = []
        for quantity in LANGS[lang]["quantities"]:
            raw = by_q.get("one", other) if quantity == "one" else by_q.get("other", other)
            sources.append((quantity, raw, _quantity_note(lang, quantity)))
    units = []
    for key, raw, extra in sources:
        unit_id = "%s:%s" % (entry.file, entry.name) + (":" + key if key else "")
        prot = protect(raw)
        note = " ".join(part for part in [entry.note, extra] if part)
        units.append(Unit(unit_id, entry.file, entry, key, prot, note, has_letters(prot)))
    return units


def english_entries(res_dir):
    """{filename: [Entry]} of every translatable entry in values/."""
    result = {}
    for filename in resource_files(res_dir):
        path = os.path.join(res_dir, "values", filename)
        with open(path, encoding="utf-8") as handle:
            if not STRING_HINT.search(handle.read()):
                continue
        entries = [e for e in parse_resources(path) if e.translatable]
        if entries:
            result[filename] = entries
    return result


def all_units(res_dir, lang):
    """[(filename, [Entry], [Unit])] in translation-priority order."""
    return [(f, entries, [u for e in entries for u in entry_units(e, lang)])
            for f, entries in english_entries(res_dir).items()]


def target_index(res_dir, lang):
    """{name: set of keys} of everything the existing values-<lang> folder already defines, over all its files."""
    folder = os.path.join(res_dir, folder_for(lang))
    index = {}
    if not os.path.isdir(folder):
        return index
    for filename in sorted(os.listdir(folder)):
        path = os.path.join(folder, filename)
        if not filename.endswith(".xml") or not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as handle:
            if not STRING_HINT.search(handle.read()):
                continue
        for entry in parse_resources(path):
            index.setdefault(entry.name, set()).update(key for key, _ in entry.items)
    return index


def entry_missing(entry, lang, index):
    """True when the target folder lacks [entry] or some unit key (plural quantity, array index) of it."""
    wanted = {unit.key for unit in entry_units(entry, lang)}
    return not wanted <= index.get(entry.name, set())


# ---------- glossary and rules ----------

def glossary_rows():
    """[(english, persian, note)] from the table in docs/translation-fa.md."""
    rows = []
    if not os.path.isfile(DOCS_FA):
        return rows
    with open(DOCS_FA, encoding="utf-8") as handle:
        for line in handle:
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if line.startswith("|") and len(cells) >= 2 and cells[0] not in ("English", "") \
                    and not set(cells[0]) <= set("-: "):
                rows.append((cells[0], cells[1], cells[2] if len(cells) > 2 else ""))
    return rows


def persian_rules():
    if not os.path.isfile(DOCS_FA):
        return ""
    with open(DOCS_FA, encoding="utf-8") as handle:
        text = handle.read()
    return text.split("## Writing rules", 1)[-1].strip() if "## Writing rules" in text else ""


def load_glossary(lang):
    """(glossary dict, complete) for [lang]; complete is False while some target terms are still empty."""
    rows = glossary_rows()
    if lang == "fa":
        return {english: persian for english, persian, _ in rows}, True
    path = os.path.join(TOOLS_DIR, "glossary", lang + ".json")
    chosen = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as handle:
            chosen = json.load(handle)
    glossary = {english: chosen.get(english, "") for english, _, _ in rows}
    return glossary, bool(glossary) and all(glossary.values())


def language_rules(lang):
    if lang == "fa":
        return persian_rules()
    path = os.path.join(TOOLS_DIR, "rules", lang + ".md")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    return ""


def read_json_lenient(path):
    """JSON from a file that may be wrapped in a markdown code fence by a chat tool."""
    with open(path, encoding="utf-8-sig") as handle:
        text = handle.read().strip()
    fence = re.match(r"^```[a-zA-Z]*\s*(.*?)\s*```$", text, re.S)
    return json.loads(fence.group(1) if fence else text)


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
