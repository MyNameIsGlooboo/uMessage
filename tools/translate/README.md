# Translation tools

Python 3, standard library only. They cut the English strings into batches for ChatGPT, write the answers back as
Android resource files and check them. Nothing here calls a translation service.

Languages: `fa` (Persian, a re-translation that replaces `values-fa`), the new `ar tr es ru hi in pt-rBR fr` and
the ten more `de it zh-rCN ja ko vi th pl uk bn` (Indonesian is `in`, the Android folder name; its language tag in
the app is `id`; Simplified Chinese is the folder `values-zh-rCN`, tag `zh-CN`).

## Run in order

```
python tools/translate/extract.py                  # all languages (or --lang ar tr)
```
1. **Glossary (new languages only).** `work/<lang>/glossary_request.txt` is the question for ChatGPT. Read its
   JSON answer, correct it, save it as `tools/translate/glossary/<lang>.json` (an object from the English term to
   the term in that language), then run `extract.py --lang <lang>` again. Persian uses the table in
   `docs/translation-fa.md`.
2. **Translate.** For each `work/<lang>/prompt/batch_NN.txt`: paste the whole file into a fresh ChatGPT chat, save
   the JSON answer as `work/<lang>/done/batch_NN.json` (a markdown code fence around it is fine).
   Order of the batches: onboarding, spam and blocked, thread, settings, then the rest.
3. `python tools/translate/apply.py --lang ar` writes `app/src/main/res/values-<lang>/`.
   A file with a missing or broken unit is not written; fix the batch or use `--allow-missing`.
4. `python tools/translate/validate.py --lang ar` reports missing ids, broken placeholders, wrong plural
   quantities, untranslated text, over-long text and bad escapes. Fix and re-run steps 3 and 4.
5. Run lint and the unit tests in Gradle, then check the app on a device (RTL for `ar` and `fa`).

Self-test: `python -m unittest discover -s tools/translate`.

## The ten more languages: `de it zh-rCN ja ko vi th pl uk bn`

Every command from the repo root. Add `--lang` to take fewer languages. They need no glossary file in advance; the
rules in `rules/<lang>.md` are already written (edit freely before step 1).

```
python tools/translate/extract.py --lang de it zh-rCN ja ko vi th pl uk bn
python tools/translate/run_codex.py glossary --lang de it zh-rCN ja ko vi th pl uk bn   # writes glossary/<lang>.json
python tools/translate/extract.py --lang de it zh-rCN ja ko vi th pl uk bn   # again: puts the glossary in the batches
python tools/translate/run_codex.py batches --lang de it zh-rCN ja ko vi th pl uk bn --workers 3
python tools/translate/apply.py --lang de it zh-rCN ja ko vi th pl uk bn
python tools/translate/validate.py --lang de it zh-rCN ja ko vi th pl uk bn
```

Read each `glossary/<lang>.json` before the second extract (the same term in every unit is the point of it).
Folders written: `values-de values-it values-zh-rCN values-ja values-ko values-vi values-th values-pl values-uk
values-bn`. Validate allows Thai and Bengali 4x the English length (others 3x) and flags Japanese without
kana or Chinese with kana (the usual model slip between the two).

## Top-up: only the strings a language does not have yet (all 19 translated languages)

After new English strings land in `values/`, translate only the new ones. `--missing-only` compares the English
ids with what `values-<lang>/*.xml` already defines (by resource name, plural quantity and array index) and writes
batches named `<tag>_NN` (default `topup`) next to the finished ones, so `run_codex.py` never skips them and no
earlier answer is overwritten. Use a new tag for every top-up. `apply.py --merge` adds only the missing entries
to the same-named file of `values-<lang>` after the entry that precedes them in the English file; every existing
translation stays byte for byte as it is (a plural that lacks a quantity is replaced as a whole).

```
python tools/translate/extract.py --missing-only --tag wave8      # every language that has a values-<lang> folder
python tools/translate/run_codex.py batches --lang fa ar tr es ru hi in pt-rBR fr de it zh-rCN ja ko vi th pl uk bn --workers 3
python tools/translate/apply.py --merge --lang fa ar tr es ru hi in pt-rBR fr de it zh-rCN ja ko vi th pl uk bn
python tools/translate/validate.py
```

`run_codex.py` prints "kept" for the old `batch_NN` prompts (their answers exist) and translates only the new ones;
a language with nothing missing gets 0 new batches. Persian and Arabic can be topped up the same way once their full run has been applied. Re-running
`extract.py --missing-only --tag wave8` clears only the old `wave8_*` files, never `done/`. Do not use the tag
`batch`.

## Files

| File | Purpose |
|---|---|
| `extract.py` | English `values/*.xml` to `work/<lang>/batch_NN.json` (about 150 units each) and `prompt/batch_NN.txt` |
| `prompt_template.md` | the instruction sent with every batch |
| `glossary_prompt.md` | the instruction that builds a language's glossary |
| `rules/<lang>.md` | address form, typography and plural notes for each new language (edit freely, then re-extract) |
| `apply.py`, `validate.py` | step 3 and 4 (`apply.py --merge` is the top-up) |
| `common.py` | parsing, token protection, plural rules per language |

## How the text is protected

`%1$s`, `%d`, `%%`, `{name}`, `\n`, `\t`, `\uXXXX` and tags (`<b>`, `&lt;b&gt;`) become tokens `⟦1⟧`, `⟦2⟧`...
in the unit text; apostrophes, quotes and `&` are shown plain and escaped again on apply. Strings with no letters
(separators, bare placeholders) are not sent and are copied unchanged. `translatable="false"` strings and
`donottranslate.xml` are skipped. Plural strings are sent once per quantity the language needs (ar six; ru, pl and uk four;
most others two; in, ja, ko, zh, vi and th one) as ids ending `:one`, `:few`... Arrays are `file:name:index`.

The vendored commons library (`third_party/commons`) is a separate module and is not extracted; it only has
English and Persian. Use `--res <its res folder>` (and `--work`) to run the same tools on it later.
