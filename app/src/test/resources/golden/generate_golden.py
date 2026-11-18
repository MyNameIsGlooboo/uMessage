"""
Writes the golden backup fixtures in this folder. Run: python -I generate_golden.py
(no arguments; it writes next to itself). The output is fully deterministic: salts and nonces are derived from the
file name, so running it again gives byte-identical files.

These fixtures were produced from the 0.4.14 backup format code (BackupCrypto, BackupPasswordForms,
MessagesImporter, SettingsBackupFormat) by this independent script. They are NOT files written by a released device
build. All content is invented; the only numbers are +15555550100 and +15555550101.

Needs the `cryptography` package for AES-GCM.
"""
import hashlib
import json
import os
import unicodedata

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.dirname(os.path.abspath(__file__))
ITERATIONS = 600_000  # BackupCrypto.ITERATIONS

ASCII_PASSWORD = "golden-ascii-pass-1"
# kaf, lam, yeh, dal and Persian digits one two three (Persian keyboard)
PERSIAN_PASSWORD = "کلید۱۲۳"


def write(name, data):
    if isinstance(data, str):
        data = data.encode("utf-8")
    with open(os.path.join(HERE, name), "wb") as f:
        f.write(data)


def fold(password):
    """BackupPasswordForms.folded"""
    ignored = {"​", "‌", "‍", "⁠", "﻿", "­", "ـ"}
    out = []
    for c in unicodedata.normalize("NFC", password):
        if c in ignored:
            continue
        if c in ("ي", "ى"):
            out.append("ی")
        elif c == "ك":
            out.append("ک")
        elif "۰" <= c <= "۹":
            out.append(chr(ord("0") + ord(c) - 0x06F0))
        elif "٠" <= c <= "٩":
            out.append(chr(ord("0") + ord(c) - 0x0660))
        else:
            out.append(c)
    return "".join(out) or unicodedata.normalize("NFC", password)


def seal(version, password, plain, name):
    """UMSGENC1/2: magic, iterations, salt, nonce prefix, sealed verifier, one last chunk."""
    seed = hashlib.sha256(name.encode("utf-8")).digest()
    salt, nonce_prefix = seed[:16], seed[16:24]
    form = unicodedata.normalize("NFC", password) if version == 1 else fold(password)
    key = hashlib.pbkdf2_hmac("sha256", form.encode("utf-8"), salt, ITERATIONS, 32)
    gcm = AESGCM(key)
    magic = b"UMSGENC" + str(version).encode("ascii")
    verifier = gcm.encrypt(nonce_prefix + (0).to_bytes(4, "big"), b"", b"\x02")
    chunk = gcm.encrypt(nonce_prefix + (1).to_bytes(4, "big"), plain, b"\x01")  # last chunk
    return magic + ITERATIONS.to_bytes(4, "big") + salt + nonce_prefix + verifier + chunk


def sms(address, body, date, typ):
    return {
        "subscriptionId": -1, "address": address, "body": body, "date": date, "dateSent": date, "locked": 0,
        "protocol": None, "read": 1, "status": -1, "type": typ, "serviceCenter": None, "backupType": "sms",
    }


MESSAGES = [
    sms("+15555550100", "Golden fixture one: hello from the 0.4.14 format.", 1700000001000, 1),
    sms("+15555550100", "سلام، این یک پیام آزمایشی است.", 1700000002000, 2),
    sms("+15555550101", "Golden fixture three: second sender.", 1700000003000, 1),
]

messages_json = json.dumps(MESSAGES, ensure_ascii=False, separators=(",", ":")) + "\n"
write("messages.json", messages_json)

XML_BODY = MESSAGES[1]["body"]
write("messages.xml", """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<smses count="3">
  <sms protocol="0" address="+15555550100" date="1700000001000" type="1" body="Golden fixture one: hello from the 0.4.14 format." read="1" status="-1" locked="0" />
  <sms protocol="0" address="+15555550100" date="1700000002000" type="2" body="%s" read="1" status="-1" locked="0" />
  <sms protocol="0" address="+15555550101" date="1700000003000" type="1" body="Golden fixture three: second sender." read="1" status="-1" locked="0" />
</smses>
""" % XML_BODY)

settings = {
    "app": "spamless", "kind": "settings-backup", "version": 1, "exportedAt": 1700000000000,
    "modeIncludesSwitch": True, "appVersion": "0.4.14",
    "rules": [
        {"kind": "BLOCK", "type": "KEYWORD", "pattern": "free prize", "note": "golden", "createdAt": 1},
        {"kind": "BLOCK", "type": "KEYWORD", "pattern": "تخفیف ویژه", "note": "", "createdAt": 2},
        {"kind": "ALLOW", "type": "NUMBER", "pattern": "+15555550100", "note": "", "createdAt": 3},
    ],
    "forwardRules": [
        {"senderType": "NUMBER", "senderPattern": "+15555550101", "bodyType": "KEYWORD", "bodyPattern": "code",
         "windowStart": 480, "windowEnd": 1200, "targets": ["+15555550100"], "sendFrom": "SAME",
         "includeCodes": True, "enabled": False, "note": "golden forward", "createdAt": 4},
    ],
    "blockedKeywords": ["lottery"],
    "settings": {"spamMode": "SMART", "purgeAfterDays": 14, "notifyOnBlock": True, "dailyForwardLimit": 30,
                 "maskForwardedCodes": True, "sendLongAsMmsAfter": 3, "signature": "golden"},
}
write("settings.json", json.dumps(settings, ensure_ascii=False, indent=2) + "\n")

plain = messages_json.encode("utf-8")
write("messages_v1_ascii.umsgenc", seal(1, ASCII_PASSWORD, plain, "messages_v1_ascii"))
write("messages_v1_persian.umsgenc", seal(1, PERSIAN_PASSWORD, plain, "messages_v1_persian"))
write("messages_v2_ascii.umsgenc", seal(2, ASCII_PASSWORD, plain, "messages_v2_ascii"))
write("messages_v2_persian.umsgenc", seal(2, PERSIAN_PASSWORD, plain, "messages_v2_persian"))
