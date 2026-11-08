# Persian (Farsi) translation

The app ships in English (`values/`) and Persian (`values-fa/`). Android picks Persian when the phone's
language is Persian; layouts mirror to right-to-left on their own.

## Glossary

Use these terms everywhere, in the app's own strings and in the vendored commons strings. The tone is the
everyday spoken Persian of popular apps (Telegram, WhatsApp, Instagram): short, plain, common loanwords are
welcome ("بلاک"، "لینک"، "بک‌آپ"). Address the user with the polite plural verb ("کنید") and avoid stiff
officialese. The 0.4.2 pass replaced the formal words below with the spoken ones (732 strings).

| English | Persian | Notes |
|---|---|---|
| Spam | اسپم | Tab and screen title: اسپم. Formerly هرزنامه. |
| Report spam | گزارش اسپم | |
| Not spam | اسپم نیست | Button that restores a message from Spam. |
| Block / Blocked | بلاک کردن / بلاک‌شده | "Block sender" = بلاک کردن فرستنده. Formerly مسدود. |
| Unblock | رفع بلاک | |
| Allow / Allowed | اجازه دادن / مجاز | allow list = لیست مجاز. |
| Rule / Rules | قانون / قانون‌ها | Kept. |
| Unknown sender | فرستنده ناشناس | Kept. |
| Verification code / one-time code | کد تأیید / کد یک‌بارمصرف | "Copy code" = کپی کد. |
| Filter mode Strict / Smart / Off | سخت‌گیرانه / هوشمند / خاموش | |
| Archive / Archived | آرشیو کردن / آرشیوشده | Formerly بایگانی. |
| Unarchive | خارج کردن از آرشیو | |
| Message / Messages | پیام / پیام‌ها | SMS = پیام (پیامک only inside the quoted Android setting «برنامهٔ پیامک»), MMS = پیام چندرسانه‌ای (MMS). |
| Conversation | گفتگو | Kept. |
| Contact / Contacts | مخاطب / مخاطب‌ها | Plural: مخاطب‌ها, not مخاطبان. |
| Sender / Sender ID | فرستنده / شناسهٔ فرستنده | Kept. |
| Keyword | کلمهٔ کلیدی | Formerly کلیدواژه; word = کلمه (not واژه). |
| Pattern (regex) | الگو | Kept. |
| Match / Matches | جور بودن / جور شدن | Formerly مطابقت / تطبیق. |
| Notification | اعلان | Kept. |
| Settings | تنظیمات | |
| Undo | برگردان | Formerly واگرد. |
| Delete | حذف | |
| Restore | بازیابی | Kept: what WhatsApp and Google Photos say. |
| Send | ارسال | Kept ("ارسال نشد"). |
| Backup | بک‌آپ | "Take a backup" = گرفتن بک‌آپ. Formerly پشتیبان. |
| Export / Import | خروجی گرفتن / وارد کردن | Formerly صادر کردن. |
| Forward (auto-forwarding) | فوروارد | Formerly بازارسال. |
| Share | اشتراک‌گذاری | Formerly هم‌رسانی. |
| Schedule (a message) | زمان‌بندی | |
| Pin (a conversation) | سنجاق کردن | |
| Mark as read / unread | علامت‌گذاری به‌عنوان خوانده‌شده / خوانده‌نشده | |
| App lock / PIN | قفل برنامه / پین | |
| Password | رمز | Formerly گذرواژه. |
| SIM | سیم‌کارت | |
| Inbox | صندوق ورودی | |
| Default SMS app | برنامهٔ پیام پیش‌فرض | |
| App | برنامه | |
| Theme | زمینه | |
| Permission | دسترسی | Formerly مجوز. |
| Location | لوکیشن | Formerly موقعیت. |
| Licenses / Copyright | لایسنس / کپی‌رایت | Formerly پروانه / حق نشر. |
| Link / Attachment | لینک / پیوست | Formerly پیوند for link. |
| Recycle Bin | سطل زباله | Formerly سطل بازیافت. |
| List (block list, allow list) | لیست | Formerly فهرست. |
| Character (text length) | کاراکتر | Formerly نویسه. |
| Wildcard | نویسهٔ عام | |
| Picture message | پیام تصویری | |
| Decision log | گزارش تصمیم‌ها | |
| Biometrics | اثر انگشت یا چهره | Formerly زیست‌سنجی. |
| Encryption | رمزنگاری | Formerly رمزگذاری. |
| Authenticate | تأیید هویت | |
| Keyboard | کیبورد | |
| Font | فونت | Formerly قلم. |
| Icons | آیکون‌ها | |
| Download | دانلود | Formerly بارگیری. |
| Restart | ریستارت | |
| Carrier | اپراتور | |
| Now / then / still | الان / بعد / هنوز | Formerly اکنون / سپس / همچنان. |
| Before / after (time) | قبل از / بعد از | Formerly پیش از / پس از. |
| Very / so / when | خیلی / پس / وقتی | Formerly بسیار / بنابراین / هنگام. |

Words that deliberately stay: بازیابی, ارسال, گفتگو, فرستنده, قانون, الگو, اعلان, مخاطب. The search-keyword
strings (`sks_*`) keep the old formal words next to the new ones, so a setting is found with either word.
Names stay as they are: uMessage, Google Drive, SMS/MMS, GPL, Material You.

## Writing rules

- Persian letters only: ی (U+06CC) and ک (U+06A9), never Arabic ي (U+064A) or ك (U+0643). A unit test
  (`PersianTranslationTest`) fails on them.
- Zero-width non-joiner (U+200C) inside compounds: می‌شود، پیام‌ها، بلاک‌شده‌ها، نمی‌توانید.
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
