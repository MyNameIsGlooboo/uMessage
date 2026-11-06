# Smart replies

Short reply chips above the composer for the newest received text, for example "Are you coming tonight?" gives
"Yes, I'll be there", "Sorry, I can't make it", "Not sure yet, I'll let you know".

- Off by default. One switch in Settings > Messages > Typing ("Smart replies").
- No AI on the phone and no network. The phrases are a curated table per language, shipped in the app as
  `app/src/main/assets/smart_replies/<language>.json`. The tables were written once, on the development side, from
  knowledge of the most common SMS in each language. No message of any user was used; the test sets are invented.
- A chip only fills the text field. It never sends. The user edits and presses Send.
- Chips follow the language of the received text (script and phrase), not the app language. The app language only
  settles a text that two tables of the same script both read.

## When chips show

`SmartReplyPolicy` (who and when) and `SmartReplyMatcher` (what) must both say yes:

- the newest text of the conversation, received (not sent), a plain SMS, less than 6 hours old;
- one person, not a group; a saved contact or a number the user has texted;
- never a service sender (a name, a short code), never a text with a link, a sum of money or four or more digits in a
  row (codes, card numbers);
- the text is short (at most 10 words), written in one script, has at most one question, and holds no veto word;
- exactly one intent fits, clearly better than any other. Anything else: nothing shows.

Chips go away when the user types, when a newer text arrives, or when the user taps the small x (kept until the next
text).

## Table format

One JSON file per language. Phrases are plain text as people type them; they are normalised when the table loads
(`SmartReplyText.normalize`): lower case, apostrophes dropped (`can't` is `cant`), punctuation and emoji removed,
accents and Turkish dotless i read as plain letters, Persian ي/ك and digits folded, ZWNJ and diacritics removed, a
letter repeated three times or more written once (`heyyy`, `سلاااام`), Persian `می`/`نمی` joined to the next word,
Arabic أ إ ة read as ا ه. So write each phrase once, naturally, without worrying about those variants.

```json
{
  "language": "en",
  "script": "latin",
  "tieBreak": false,
  "minConfidence": 0.85,
  "maxWords": 8,
  "fillers": ["hey", "hi", "bro"],
  "veto": ["not", "never", "hospital", "money", "code"],
  "intents": [
    {
      "id": "are_you_coming",
      "statement": false,
      "exact": ["can you make it"],
      "contains": ["are you coming"],
      "veto": ["back", "from"],
      "replies": ["Yes, I'll be there", "Sorry, I can't make it", "Not sure yet, I'll let you know"]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `language` | Code of the language ("en", "fa", "tr", "ar"). Also the value compared with the app language. |
| `script` | `latin` or `arabic`. A table only reads texts written entirely in its script. |
| `tieBreak` | When two tables of one script read the same text and the app language is neither, the table with `tieBreak` wins. Only `fa` has it (Persian and Arabic share some phrases). |
| `minConfidence` | `exact` counts 1.0, `contains` counts 0.85. Set 1.0 to make a language use `exact` only. |
| `maxWords` | A `contains` phrase only counts in a text of at most this many words. |
| `fillers` | Greeting and address words that may stand before or after a phrase and are ignored (never the only word). |
| `veto` | Table-wide: a text holding any of these (whole words or phrase) gets no chip. Use it for sensitive words (death, hospital, police), money, codes, negations that flip a question. |
| `intents[].exact` | Phrases that must be the whole text (after fillers). The safe way: use it for greetings, thanks, "ok", "bye", wishes. |
| `intents[].contains` | Phrases that may sit inside a short text. Only for questions that carry the request ("are you coming"). Pair them with `veto` words ("back", "when", "who"). |
| `intents[].veto` | Cancels this intent only. |
| `intents[].statement` | True: the intent never applies to a text with a question mark ("ok?" is not a confirmation). |
| `intents[].replies` | Two or three short replies (at most 60 characters), neutral in gender and tone. Persian: informal-polite, as in `docs/translation-fa.md`; write ZWNJ as `‌`. |

An intent id is the same across languages where it means the same thing, but it does not have to be.

## The accuracy rule

A wrong chip is worse than none. Add a phrase only when you are sure that the reply fits every message that can
produce it. When a phrase has two meanings ("what time is it" asks for the clock, "what time?" asks for the plan),
leave it out. Leave plain "sorry" and "yes"/"no" out too. Prefer `exact` over `contains`.

Replies must read right for either gender and any age; avoid verbs and adjectives that change with the speaker's
gender (Arabic especially).

## Tests that guard the tables

All run in the unit tests, on the files in the source tree:

- `SmartReplyTablesTest`: two or three replies per intent, no phrase in two intents of one table, no phrase that
  contains its own veto word, no empty or over-long phrase, the four shipped languages.
- `SmartReplyAccuracyTest`: per language, a labelled set `app/src/test/resources/smart_replies/eval_<language>.txt`,
  one text per line as `intent id|message`; `NONE` as the id means no chip may show. The set holds invented texts:
  the first part should trigger the right intent, the second part must trigger nothing (near-misses, negations,
  other languages, codes, money, links, two questions, long texts). The test fails on any chip where `NONE` is
  expected, on any wrong intent, and when fewer than 80% of the positives get their chip.
  Each set needs at least 30 positives and 30 `NONE` texts.
- `SmartReplyMatcherTest`, `SmartReplyTextTest`, `SmartReplyPolicyTest`: behaviour of the matcher, the normaliser and the
  policy.

Never put a real message into a set.

## Adding a language

1. Copy the table of the closest language to `assets/smart_replies/<code>.json`. Set `language` and `script`.
2. Translate the intents you can write **accurately**, in the way people actually type that language in SMS (including
   the common spelling variants and abbreviations). Skip an intent rather than guess. A small table is fine.
3. Fill `fillers` and `veto` (sensitive words, money and code words, the language's negations) for that language.
4. Write `app/src/test/resources/smart_replies/eval_<code>.txt`: at least 30 invented texts that should trigger and
   30 that must trigger nothing, including near-misses of every intent that uses `contains`.
5. Run `:app:testFossDebugUnitTest`. Ask a native speaker to read every phrase and every reply.
6. No code change is needed: the tables are loaded from the assets folder by name. A language without a table gets no
   chips. A language whose labelled set fails is not shipped.

## Known limits

- Persian written in Latin letters ("khobi?") is not read; Latin text goes to the Latin tables.
- Arabic covers a core set of safe phrases (no dialect-specific questions).
- Persian and Arabic spell some phrases alike ("سلام", "عيد مبارك"); the app language decides, then Persian.
- Messages sent as MMS (pictures, some long texts) never get chips.
