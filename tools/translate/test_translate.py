"""Self-test with invented data: python -m unittest discover -s tools/translate  (or: cd tools/translate; python -m unittest)."""
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apply as apply_mod  # noqa: E402
import common as c  # noqa: E402
import extract  # noqa: E402
import validate  # noqa: E402

ENGLISH = """<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">
    <!-- Title of the first screen. -->
    <string name="welcome_title">Welcome to uMessage</string>
    <string name="greeting">Hello %1$s, you have %2$d new messages.\\nDon\\'t worry &amp; relax.</string>
    <string name="bold_hint">Tap &lt;b&gt;Block&lt;/b&gt; to stop {sender}</string>
    <string name="separator">" · "</string>
    <string name="brand" translatable="false">uMessage</string>
    <string name="percent">100%% sure</string>
    <string name="at_sign">\\@home is "here"</string>
    <plurals name="message_count" tools:ignore="ImpliedQuantity">
        <item quantity="one">%d message</item>
        <item quantity="other">%d messages</item>
    </plurals>
    <string-array name="choices">
        <item>First choice</item>
        <item>Second choice</item>
    </string-array>
</resources>
"""

SECOND = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="only_one">Settings saved</string>
</resources>
"""


def fake_translation(unit):
    """Cyrillic stand-in for a translation that keeps every token."""
    marks = "".join(" ⟦%d⟧" % n for n in range(1, len(unit.prot.tokens) + 1))
    return "Перевод текста" + marks


class Workspace(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.res = os.path.join(self.root, "res")
        self.work = os.path.join(self.root, "work")
        os.makedirs(os.path.join(self.res, "values"))
        for name, text in (("strings_demo.xml", ENGLISH), ("strings_second.xml", SECOND)):
            with open(os.path.join(self.res, "values", name), "w", encoding="utf-8", newline="\n") as handle:
                handle.write(text)

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def translate_all(self, lang, mutate=None):
        extract.write_language(self.res, self.work, lang)
        done = os.path.join(self.work, lang, "done")
        for name in sorted(os.listdir(os.path.join(self.work, lang))):
            if not name.endswith(".json"):
                continue
            with open(os.path.join(self.work, lang, name), encoding="utf-8") as handle:
                batch = json.load(handle)
            units = {u.id: u for _, _, us in c.all_units(self.res, lang) for u in us}
            answer = {"units": [{"id": i["id"], "tr": fake_translation(units[i["id"]])} for i in batch["units"]]}
            if mutate:
                mutate(answer)
            with open(os.path.join(done, name), "w", encoding="utf-8") as handle:
                handle.write("```json\n" + json.dumps(answer, ensure_ascii=False) + "\n```")


class ProtectTest(unittest.TestCase):
    def test_tokens_and_round_trip(self):
        raw = "Hello %1$s, you have %2$d new.\\nDon\\'t &amp; {x} &lt;b&gt;ok&lt;/b&gt;"
        prot = c.protect(raw)
        self.assertEqual(prot.tokens, ["%1$s", "%2$d", "\\n", "{x}", "&lt;b&gt;", "&lt;/b&gt;"])
        self.assertIn("Don't & ", prot.text)
        self.assertEqual(c.restore(prot.text, prot), raw)

    def test_translator_text_is_escaped(self):
        prot = c.protect("Open %s")
        out = c.restore("L'app \"x\" & <b> ⟦1⟧", prot)
        self.assertEqual(out, "L\\'app \\\"x\\\" &amp; &lt;b&gt; %s")

    def test_leading_at_sign_and_tolerant_brackets(self):
        prot = c.protect("%d new")
        self.assertEqual(c.restore("@ ⟧ 1 ⟦", prot), "\\@ %d")

    def test_broken_tokens_raise(self):
        prot = c.protect("Hi %1$s and %2$s")
        for bad in ("Hi ⟦1⟧", "Hi ⟦1⟧ ⟦1⟧", "Hi ⟦1⟧ ⟦2⟧ ⟦3⟧"):
            with self.assertRaises(ValueError):
                c.restore(bad, prot)

    def test_quoted_string_keeps_quotes(self):
        prot = c.protect('" · "')
        self.assertTrue(prot.quoted)
        self.assertEqual(c.restore(" · ", prot), '" · "')

    def test_plural_form_may_drop_the_number(self):
        prot = c.protect("%d message")
        self.assertEqual(c.restore("one message", prot, may_drop_numbers=True), "one message")
        with self.assertRaises(ValueError):
            c.restore("one message", prot)


class ExtractTest(Workspace):
    def test_batches_units_and_plurals(self):
        count, units, _ = extract.write_language(self.res, self.work, "ru", size=5)
        self.assertGreater(count, 1)
        ids = []
        for name in sorted(os.listdir(os.path.join(self.work, "ru"))):
            if name.endswith(".json"):
                with open(os.path.join(self.work, "ru", name), encoding="utf-8") as handle:
                    ids += [u["id"] for u in json.load(handle)["units"]]
        self.assertEqual(len(ids), units)
        self.assertEqual(len(ids), len(set(ids)))
        self.assertNotIn("strings_demo.xml:brand", ids)
        self.assertNotIn("strings_demo.xml:separator", ids)
        self.assertEqual([i for i in ids if "message_count" in i],
                         ["strings_demo.xml:message_count:" + q for q in ("one", "few", "many", "other")])
        self.assertIn("strings_demo.xml:choices:1", ids)

    def test_language_plural_sets(self):
        for lang, expected in (("ar", 6), ("in", 1), ("fr", 2), ("tr", 2)):
            extract.write_language(self.res, self.work, lang)
            units = [u for _, _, us in c.all_units(self.res, lang) for u in us if "message_count" in u.id]
            self.assertEqual(len(units), expected, lang)

    def test_batch_has_tokens_note_glossary_and_prompt(self):
        extract.write_language(self.res, self.work, "fa")
        with open(os.path.join(self.work, "fa", "batch_01.json"), encoding="utf-8") as handle:
            batch = json.load(handle)
        by_id = {u["id"]: u for u in batch["units"]}
        self.assertIn("⟦1⟧", by_id["strings_demo.xml:greeting"]["en"])
        self.assertEqual(by_id["strings_demo.xml:welcome_title"]["note"], "Title of the first screen.")
        self.assertEqual(batch["glossary"].get("Spam"), "هرزنامه")
        with open(os.path.join(self.work, "fa", "prompt", "batch_01.txt"), encoding="utf-8") as handle:
            prompt = handle.read()
        self.assertNotIn("{{", prompt)
        self.assertIn("strings_demo.xml:greeting", prompt)

    def test_new_language_without_glossary_gets_a_request(self):
        extract.write_language(self.res, self.work, "de")
        self.assertTrue(os.path.isfile(os.path.join(self.work, "de", "glossary_request.txt")))

    def test_rerun_removes_stale_batches(self):
        extract.write_language(self.res, self.work, "ru", size=3)
        many = len([n for n in os.listdir(os.path.join(self.work, "ru")) if n.endswith(".json")])
        extract.write_language(self.res, self.work, "ru", size=500)
        few = len([n for n in os.listdir(os.path.join(self.work, "ru")) if n.endswith(".json")])
        self.assertLess(few, many)


class ApplyAndValidateTest(Workspace):
    def test_full_round_trip_is_valid(self):
        self.translate_all("ru")
        written, skipped, problems = apply_mod.apply_language(self.res, self.work, "ru")
        self.assertEqual(sorted(written), ["strings_demo.xml", "strings_second.xml"])
        self.assertEqual((skipped, problems), ([], []))
        path = os.path.join(self.res, "values-ru", "strings_demo.xml")
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        self.assertIn('xmlns:tools="http://schemas.android.com/tools"', text)
        self.assertIn('quantity="few"', text)
        self.assertIn('<string name="separator">" · "</string>', text)
        self.assertNotIn("brand", text)
        errors, warnings, stats = validate.validate_language(self.res, "ru")
        self.assertEqual(errors, [])
        self.assertEqual(stats["files"], 2)

    def test_persian_goes_to_values_fa_and_keeps_file_names(self):
        self.translate_all("fa")
        apply_mod.apply_language(self.res, self.work, "fa")
        self.assertTrue(os.path.isfile(os.path.join(self.res, "values-fa", "strings_second.xml")))

    def test_broken_token_leaves_file_unwritten(self):
        def break_one(answer):
            for item in answer["units"]:
                if item["id"].endswith(":greeting"):
                    item["tr"] = "Привет"

        self.translate_all("ru", break_one)
        written, skipped, problems = apply_mod.apply_language(self.res, self.work, "ru")
        self.assertEqual(skipped, ["strings_demo.xml"])
        self.assertTrue(any("greeting" in p for p in problems))
        self.assertFalse(os.path.exists(os.path.join(self.res, "values-ru", "strings_demo.xml")))
        written, skipped, _ = apply_mod.apply_language(self.res, self.work, "ru", allow_missing=True)
        self.assertEqual(sorted(written), ["strings_demo.xml", "strings_second.xml"])

    def test_validate_finds_the_usual_mistakes(self):
        self.translate_all("ru")
        apply_mod.apply_language(self.res, self.work, "ru")
        path = os.path.join(self.res, "values-ru", "strings_demo.xml")
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        text = text.replace("%1$s", "")  # lost placeholder
        text = text.replace('<item quantity="few">', '<item quantity="zero">')  # wrong quantity
        text = text.replace("Перевод текста", "L'app", 1)  # unescaped apostrophe
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        errors, _, _ = validate.validate_language(self.res, "ru")
        joined = "\n".join(errors)
        self.assertIn("greeting: placeholders", joined)
        self.assertIn("missing plural quantities ['few']", joined)
        self.assertIn("not used by this language", joined)
        self.assertIn("without a backslash", joined)

    def test_validate_reports_missing_file_malformed_xml_and_english(self):
        self.translate_all("ru")
        apply_mod.apply_language(self.res, self.work, "ru")
        os.remove(os.path.join(self.res, "values-ru", "strings_second.xml"))
        path = os.path.join(self.res, "values-ru", "strings_demo.xml")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("<resources><string name='a'>x</resources>")
        errors, _, _ = validate.validate_language(self.res, "ru")
        self.assertTrue(any("not well-formed" in e for e in errors))
        self.assertTrue(any("strings_second.xml: file is missing" in e for e in errors))

    def test_leftover_english_and_length_and_letters(self):
        os.makedirs(os.path.join(self.res, "values-fa"))
        with open(os.path.join(self.res, "values-fa", "strings_second.xml"), "w", encoding="utf-8") as handle:
            handle.write('<resources><string name="only_one">Settings saved</string></resources>')
        errors, _, _ = validate.validate_language(self.res, "fa", leftover=0.0)
        self.assertTrue(any("look untranslated" in e for e in errors))
        with open(os.path.join(self.res, "values-fa", "strings_second.xml"), "w", encoding="utf-8") as handle:
            handle.write('<resources><string name="only_one">' + "ي" * 60 + "</string></resources>")
        errors, _, _ = validate.validate_language(self.res, "fa")
        joined = "\n".join(errors)
        self.assertIn("longer than 3x", joined)
        self.assertIn("wrong letter U+064A", joined)

    def test_main_returns_error_status_without_batches(self):
        self.assertEqual(apply_mod.main(["--res", self.res, "--work", self.work]), 1)


def write_target(res, lang, filename, body):
    path = os.path.join(res, c.folder_for(lang), filename)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n%s</resources>\n' % body)
    return path


class MoreLanguagesTest(Workspace):
    def test_plural_sets_of_the_ten_more_languages(self):
        expected = {"de": 2, "it": 2, "zh-rCN": 1, "ja": 1, "ko": 1, "vi": 1, "th": 1, "pl": 4, "uk": 4, "bn": 2}
        self.assertEqual(sorted(expected), sorted(c.MORE_LANGS))
        for lang, count in expected.items():
            units = [u for _, _, us in c.all_units(self.res, lang) for u in us if "message_count" in u.id]
            self.assertEqual(len(units), count, lang)
            if count > 1:
                self.assertIn(lang, c.QUANTITY_HINT)
        self.assertEqual(c.folder_for("zh-rCN"), "values-zh-rCN")
        self.assertEqual(c.folder_for("uk"), "values-uk")

    def test_single_form_note_names_the_language(self):
        extract.write_language(self.res, self.work, "ja")
        with open(os.path.join(self.work, "ja", "batch_01.json"), encoding="utf-8") as handle:
            notes = [u.get("note", "") for u in json.load(handle)["units"]]
        self.assertTrue(any("Japanese has one plural form" in n for n in notes))

    def test_every_new_language_applies(self):
        for lang in c.MORE_LANGS:
            self.translate_all(lang)
            _, skipped, problems = apply_mod.apply_language(self.res, self.work, lang)
            self.assertEqual((skipped, problems), ([], []), lang)
        errors, _, _ = validate.validate_language(self.res, "uk")  # the stand-in translation is Cyrillic
        self.assertEqual(errors, [])

    def write_one(self, lang, text):
        write_target(self.res, lang, "strings_second.xml", '<string name="only_one">%s</string>' % text)
        return [e for e in validate.validate_language(self.res, lang, leftover=0.0)[0] if "only_one" in e]

    def test_short_cjk_and_thai_are_fine_and_english_is_caught(self):
        for lang, text in (("ja", "保存しました"), ("zh-rCN", "设置已保存"), ("ko", "설정을 저장했어요"),
                           ("th", "บันทึกการตั้งค่าแล้ว"), ("bn", "সেটিংস সংরক্ষিত"), ("uk", "Налаштування збережено")):
            self.assertEqual(self.write_one(lang, text), [], lang)
        for lang in ("ja", "zh-rCN", "ko", "th", "bn", "uk"):
            self.assertTrue(any("look untranslated" in e for e in self.write_one(lang, "Settings saved")), lang)

    def test_thai_and_bengali_get_a_looser_length_limit(self):
        self.assertTrue(any("longer than 4x" in e for e in self.write_one("th", "ก" * 70)))
        self.assertFalse(any("longer than" in e for e in self.write_one("th", "ก" * 50)))
        self.assertTrue(any("longer than 3x" in e for e in self.write_one("de", "x" * 60)))

    def test_japanese_needs_kana_and_chinese_has_none(self):
        self.assertTrue(any("without kana" in e for e in self.write_one("ja", "设置已保存设置已保存设置")))
        self.assertTrue(any("kana in Chinese" in e for e in self.write_one("zh-rCN", "設定を保存しました")))


class MissingOnlyTest(Workspace):
    LINES = {
        "welcome_title": '    <string name="welcome_title">Добро</string>\n',
        "greeting": '    <string name="greeting">Привет %1$s %2$d</string>\n',
        "choices": '    <string-array name="choices">\n        <item>Один</item>\n        <item>Два</item>\n'
                   '    </string-array>\n',
    }

    def body(self, *names):
        return "".join(self.LINES[n] for n in names)

    def answer_all(self, lang):
        """Fake answers for every batch file in work/<lang> (first run and top-ups alike)."""
        units = {u.id: u for _, _, us in c.all_units(self.res, lang) for u in us}
        for name in os.listdir(os.path.join(self.work, lang)):
            if not name.endswith(".json"):
                continue
            with open(os.path.join(self.work, lang, name), encoding="utf-8") as handle:
                batch = json.load(handle)
            marks = lambda unit: "".join(" ⟦%d⟧" % n for n in range(1, len(unit.prot.tokens) + 1))  # noqa: E731
            answer = {"units": [{"id": i["id"], "tr": "Text" + marks(units[i["id"]])} for i in batch["units"]]}
            with open(os.path.join(self.work, lang, "done", name), "w", encoding="utf-8") as handle:
                json.dump(answer, handle)

    def test_extract_emits_only_missing_entries_in_tagged_batches(self):
        write_target(self.res, "de", "strings_demo.xml", self.body("welcome_title", "greeting", "choices"))
        write_target(self.res, "de", "strings_second.xml", '    <string name="only_one">Gespeichert</string>\n')
        extract.write_language(self.res, self.work, "de", missing_only=True, tag="wave8")
        names = sorted(os.listdir(os.path.join(self.work, "de")))
        self.assertIn("wave8_01.json", names)
        self.assertNotIn("batch_01.json", names)
        self.assertTrue(os.path.isfile(os.path.join(self.work, "de", "prompt", "wave8_01.txt")))
        with open(os.path.join(self.work, "de", "wave8_01.json"), encoding="utf-8") as handle:
            ids = [u["id"] for u in json.load(handle)["units"]]
        self.assertEqual({i.split(":")[1] for i in ids}, {"bold_hint", "percent", "at_sign", "message_count"})

    def test_finished_language_gives_no_batches_and_batch_is_not_a_tag(self):
        self.translate_all("de")
        apply_mod.apply_language(self.res, self.work, "de")
        count, units, _ = extract.write_language(self.res, self.work, "de", missing_only=True, tag="t1")
        self.assertEqual((count, units), (0, 0))
        with self.assertRaises(ValueError):
            extract.write_language(self.res, self.work, "de", missing_only=True, tag="batch")

    def test_main_defaults_to_languages_that_have_a_folder(self):
        write_target(self.res, "pl", "strings_second.xml", '    <string name="only_one">Zapisano</string>\n')
        extract.main(["--res", self.res, "--work", self.work, "--missing-only"])
        self.assertEqual(os.listdir(self.work), ["pl"])

    def test_merge_adds_in_english_order_and_keeps_existing_text(self):
        path = write_target(self.res, "de", "strings_demo.xml", self.body("welcome_title", "choices"))
        with open(path, encoding="utf-8") as handle:
            before = handle.read()
        extract.write_language(self.res, self.work, "de", missing_only=True, tag="t1")
        self.answer_all("de")
        written, skipped, problems = apply_mod.apply_language(self.res, self.work, "de", merge=True)
        self.assertEqual((skipped, problems), ([], []))
        self.assertEqual(sorted(written), ["strings_demo.xml", "strings_second.xml"])
        with open(path, encoding="utf-8") as handle:
            after = handle.read()
        ET.fromstring(after.encode("utf-8"))
        order = [m.group(1) for m in re.finditer(r'<(?:string|plurals|string-array) name="(\w+)"', after)]
        self.assertEqual(order, ["welcome_title", "greeting", "bold_hint", "separator", "percent", "at_sign",
                                 "message_count", "choices"])
        for line in before.splitlines():
            if "<string " in line or "<item>" in line:
                self.assertIn(line, after)
        self.assertIn('<item quantity="one">', after)
        self.assertTrue(os.path.isfile(os.path.join(self.res, "values-de", "strings_second.xml")))
        written, _, _ = apply_mod.apply_language(self.res, self.work, "de", merge=True)
        self.assertEqual(written, [])

    def test_merge_replaces_a_plural_that_lacks_a_quantity_and_adds_the_namespace(self):
        body = '    <plurals name="message_count">\n        <item quantity="other">%d x</item>\n    </plurals>\n'
        path = write_target(self.res, "uk", "strings_demo.xml", body)
        write_target(self.res, "uk", "strings_second.xml", '    <string name="only_one">Готово</string>\n')
        extract.write_language(self.res, self.work, "uk", missing_only=True, tag="t1")
        self.answer_all("uk")
        _, _, problems = apply_mod.apply_language(self.res, self.work, "uk", merge=True)
        self.assertEqual(problems, [])
        with open(path, encoding="utf-8") as handle:
            after = handle.read()
        self.assertEqual(after.count('name="message_count"'), 1)
        for quantity in ("one", "few", "many", "other"):
            self.assertIn('quantity="%s"' % quantity, after)
        self.assertIn('xmlns:tools="http://schemas.android.com/tools"', after)
        ET.fromstring(after.encode("utf-8"))

    def test_merge_leaves_a_file_alone_when_an_answer_is_missing(self):
        path = write_target(self.res, "de", "strings_demo.xml", self.body("welcome_title"))
        with open(path, encoding="utf-8") as handle:
            before = handle.read()
        extract.write_language(self.res, self.work, "de", missing_only=True, tag="t1")
        _, skipped, problems = apply_mod.apply_language(self.res, self.work, "de", merge=True)
        self.assertIn("strings_demo.xml", skipped)
        self.assertTrue(problems)
        with open(path, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), before)


class RepoTest(unittest.TestCase):
    def test_real_strings_round_trip_and_every_language_extracts(self):
        for lang in c.ALL_LANGS:
            for _, entries, _ in c.all_units(c.DEFAULT_RES, lang):
                for entry in entries:
                    for unit in c.entry_units(entry, lang):
                        if unit.translate:
                            c.restore(unit.prot.text, unit.prot)


if __name__ == "__main__":
    unittest.main()
