# Persian (Farsi) translation

The app ships in English (`values/`) and Persian (`values-fa/`). Android picks Persian when the phone's
language is Persian; layouts mirror to right-to-left on their own.

## Glossary

Use these terms everywhere, in the app's own strings and in the vendored commons strings. The tone is the
of good Persian-language apps (Google's Persian UI, for example): short, polite, plain. Address the user with
the polite plural verb ("کنید") and avoid stiff officialese.

| English | Persian | Notes |
|---|---|---|
| Spam | هرزنامه | The term Google Messages and Gmail use in Persian. "Spam stopped" = هرزنامهٔ متوقف‌شده. |
| Report spam | گزارش هرزنامه | |
| Not spam | هرزنامه نیست | Button that restores a blocked message. |
| Block / Blocked | مسدود کردن / مسدودشده | "Block sender" = مسدود کردن فرستنده. |
| Blocked (tab / screen) | مسدودشده‌ها | Blocked messages screen = پیام‌های مسدودشده. |
| Unblock | رفع مسدودی | |
| Allow / Allowed | اجازه دادن / مجاز | allow list = فهرست مجاز. |
| Rule / Rules | قانون / قانون‌ها | "Allowed and blocked rules" = قانون‌های مجاز و مسدود. |
| Unknown sender | فرستنده ناشناس | |
| Verification code / one-time code | کد تأیید / کد یک‌بارمصرف | "Copy code" = کپی کد. |
| Filter mode Strict / Smart / Off | سخت‌گیرانه / هوشمند / خاموش | |
| Archive / Archived | بایگانی کردن / بایگانی‌شده | Archived screen = بایگانی. |
| Message / Messages | پیام / پیام‌ها | SMS = پیامک, MMS = پیام چندرسانه‌ای (MMS). |
| Conversation | گفتگو | |
| Contact | مخاطب | |
| Sender / Sender ID | فرستنده / شناسهٔ فرستنده | |
| Keyword | کلیدواژه | |
| Pattern (regex) | الگو | |
| Notification | اعلان | |
| Settings | تنظیمات | |
| Undo | واگرد | As in Google's Persian UI. |
| Delete | حذف | |
| Restore | بازیابی | |
| Backup / Export / Import | پشتیبان / صادر کردن / وارد کردن | |
| Schedule (a message) | زمان‌بندی | |
| Pin (a conversation) | سنجاق کردن | |
| Mark as read / unread | علامت‌گذاری به‌عنوان خوانده‌شده / خوانده‌نشده | |
| App lock / PIN | قفل برنامه / پین | |
| SIM | سیم‌کارت | |
| Inbox | صندوق ورودی | |
| Default SMS app | برنامهٔ پیامک پیش‌فرض | |
| App | برنامه | |
| Theme | زمینه | |
| Permission | مجوز | |
| Licenses | پروانه‌ها | مجوز is taken by Permission. |
| Link / Attachment | پیوند / پیوست | |
| Forward / Share | بازارسال / هم‌رسانی | |
| Recycle Bin | سطل بازیافت | |
| Unarchive | خارج کردن از بایگانی | |
| Character (text length) | نویسه | |
| Wildcard | نویسهٔ عام | |
| Block list | فهرست مسدودی | |
| Picture message | پیام تصویری | |
| Decision log | گزارش تصمیم‌ها | |
| Biometrics | زیست‌سنجی | |
| Carrier | اپراتور | |

## Writing rules

- Persian letters only: ی (U+06CC) and ک (U+06A9), never Arabic ي (U+064A) or ك (U+0643). A unit test
  (`PersianTranslationTest`) fails on them.
- Zero-width non-joiner (U+200C) inside compounds: می‌شود، پیام‌ها، مسدودشده‌ها، نمی‌توانید.
- Keep placeholders (`%1$s`, `%d`, `%%`), HTML tags, `\n` and `\'` exactly as in English. The unit test
  checks that every Persian string has the same placeholders as the English one.
- Digits: counts, times and sizes show Persian digits (۰-۹). `%d` placeholders get them from the app's
  locale; a number written into a Persian string ("پس از ۳۰ ثانیه") uses Persian digits too; code that
  builds a number label uses `localizedNumber` / `localizeDigits` (ui/components/LocalizedNumbers.kt),
  never `toString()`. A phone number or number-only sender shown on screen (list, Archived, search,
  thread header, Details) goes through `displayNumber` (ui/components/PhoneNumberDisplay.kt), which
  groups it and writes it in Persian digits in the Persian UI (VS-38); copied, dialled and stored
  numbers keep their own digits. Codes, example numbers, versions in licence ids and message text keep
  Latin digits.
- Plurals: Persian uses quantities `one` and `other`; give both. In Persian `one` also covers 0, so lint's
  `ImpliedQuantity` flags a `one` item without `%d`; where the count is never 0 the plural carries
  `tools:ignore="ImpliedQuantity"`.
- The English string files no longer suppress `MissingTranslation`, so lint reports any English string that
  lacks a Persian one.
- Brand and technical strings ("uMessage", package name, SMS/MMS/SIM labels, file sizes) live in
  `donottranslate.xml` and are not translated.
- Use Persian punctuation: ، (comma), ؟ (question mark), « » for quotes.
- Don't change the English wording when translating; a new English string gets its Persian one in the
  same change.
