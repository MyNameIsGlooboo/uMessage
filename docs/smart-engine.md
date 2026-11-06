# Smart reply engine: question understanding (second layer)

The first layer (`helpers/smartreply`, tables in `assets/smart_replies/<lang>.json`) matches short fixed phrases such as
"thanks" or "on my way". The second layer, `helpers/smartreply/engine`, runs ONLY when the first layer found nothing.
It reads what a short incoming message asks and answers with fitting chips. It is rule based and runs on the phone.

## Data file: `app/src/main/assets/smart_engine/<language>.json`

One file per app language: en fa ar tr zh ja ko th bn hi ru uk fr de es it pt pl id vi. UTF-8 without BOM.

```json
{
  "language": "en",
  "script": "latin",
  "maxWords": 14,
  "yesNo": {
    "starters": ["are you", "do you", "can you", "is it"],
    "endings": ["right", "ok", "okay"],
    "yes": ["Yes", "Yes, sure", "Sure", "Of course"],
    "no": ["No", "Not really", "Sorry, no", "I can't"],
    "later": ["Let me check", "Not sure yet", "I'll let you know", "Maybe"]
  },
  "when": { "cues": ["when", "what time"], "replies": ["Tomorrow", "Tonight", "In an hour", "Later today", "I'll tell you soon"] },
  "where": { "cues": ["where are you", "where is", "where should"], "replies": ["At home", "At work", "On my way", "I'll send the location"] },
  "choice": { "separators": ["or"], "maxOptionWords": 3, "either": ["Either is fine", "You choose", "Whatever you like"] },
  "request": { "cues": ["can you send", "please send", "send me", "could you"], "replies": ["Sure", "OK, will do", "Give me a minute", "Sending now"] },
  "invite": {
    "cues": ["shall we", "let's", "want to", "do you want to", "are you free"],
    "yes": ["Sounds good", "I'd love to", "Yes, let's"],
    "no": ["Sorry, I can't", "Not today", "Maybe another time"],
    "later": ["Let me check", "Maybe later", "I'll let you know"]
  },
  "register": {
    "formal": ["would you", "could you please"],
    "informal": ["wanna", "gonna"]
  },
  "formal": {
    "yes": ["Yes, certainly"], "no": ["I am afraid not"], "later": ["Let me check and get back to you"]
  }
}
```

Fields:
- `language` equals the file name; `script` uses the same values as the phrase tables (latin, arabic, han, japanese,
  hangul, thai, devanagari, bengali, cyrillic). For Persian and Arabic use `arabic`.
- `maxWords`: longest message the engine reads (characters for han, japanese, hangul, thai).
- Every `cues`, `starters`, `endings`, `separators`, `register` entry is a lower case phrase in plain spelling
  (the engine normalises incoming text the same way as the phrase tables: case, accents, Arabic look-alike letters,
  ZWNJ, digits). `starters` match at the start of the text; all other cues match as whole words / phrases anywhere
  (substrings for compact scripts). Include common spellings, informal forms and common typos without diacritics.
- Reply pools: 4 to 8 natural variants each, 40 characters or fewer, native-sounding, gender neutral where the
  language allows, no emoji. `formal` is optional; when present it replaces the matching pool for formal messages.
- `register.formal` / `register.informal`: words or phrases in the INCOMING text that show the sender is formal
  (Persian شما verb forms, German Sie, French vous, Spanish usted, Russian вы, Turkish siz, polite keigo, honorific
  endings...) or informal (تو forms, du, tu, tú, ты, sen...). Omit the block for languages without a register
  distinction. Pools in `yes/no/later` of the base are the informal/neutral ones.

## Rules the engine applies (so data authors know what is safe)

1. Phrase-table match always wins. The engine only runs when the first layer found nothing.
2. The existing policy gates stay: 1:1 thread, known sender, not a service sender, newest message, under 6 hours, no
   link, no run of 4+ digits, no money amount, one script, under `maxWords`.
3. The message must be a question: it ends with a question mark (`?`, `؟`, `？`) or its normalised text starts with a
   yes/no starter (also for messages without a question mark). Two or more question marks, or a question mark
   followed by more text, give nothing (compound questions are not answered by a chip).
4. Type is picked in this order: CHOICE, WHEN, WHERE, INVITE, REQUEST, YES_NO. First type whose cue matches wins.
5. Chips:
   - YES_NO and INVITE: one random-from-pool chip each from `yes`, `no`, `later` (seeded, the order stays yes, no, later).
   - WHEN, WHERE, REQUEST: three different chips from the pool (seeded shuffle).
   - CHOICE: the two options pulled from the message (the words either side of a separator, at most
     `maxOptionWords` each, stripped of question words and fillers, first letter capitalised) then one `either` chip.
     If the options cannot be pulled cleanly the engine shows nothing.
6. A wrong chip is worse than none: ambiguous, long, or odd messages give nothing.

## Eval sets: `app/src/test/resources/smart_engine/eval_engine_<language>.txt`

Lines `EXPECTED|invented message`; lines starting with `#` are comments. EXPECTED is one of `YES_NO`, `WHEN`,
`WHERE`, `CHOICE:option a,option b` (normalised lower case options), `REQUEST`, `INVITE`, `NONE`. At least 25
positive lines (cover every type, spelling variants, with and without question mark) and 25 `NONE` lines (statements,
long text, two questions, near-misses, questions the engine should not answer such as "why", "how much", "who",
messages with links, codes, money) per language. Invented texts only, never real messages. The Kotlin test reads
these files; all NONE lines must show nothing, no wrong type, and recall must stay at or above 0.8.
