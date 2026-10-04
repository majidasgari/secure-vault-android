#!/usr/bin/env python3
"""Generate the TOTP fixture the Kotlin tests check against.

The fixture is produced by the **desktop** implementation (`vault.core.totp` and
`vault.core.credentials.parse_body`), so the Android/JVM code is verified against the same
implementation the desktop and the browser add-on use — not against a second copy of the same
reasoning. Every row is a stored value plus a fixed timestamp, which makes the expected code
reproducible forever.

Run it with the desktop repository's interpreter so `vault` is importable:

    cd <android repo>
    PYTHONPATH=/path/to/secure-vault/src /path/to/secure-vault/.venv/bin/python tools/make_totp_fixture.py

Writes `app/src/test/resources/fixture/totp_vectors.json`. The secrets here are the RFC 6238
seeds and the well-known test secret — never a real credential.
"""

from __future__ import annotations

import base64
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "app" / "src" / "test" / "resources" / "fixture" / "totp_vectors.json"

try:
    from vault.core import totp
    from vault.core.credentials import parse_body
except ImportError:  # pragma: no cover - the caller must point PYTHONPATH at the desktop repo
    sys.exit(
        "cannot import `vault` — run with PYTHONPATH=<secure-vault>/src and that repo's venv python"
    )


def b32(ascii_seed: str) -> str:
    """Base32 form of an ASCII seed (how a KeePass/browser export stores it)."""
    return base64.b32encode(ascii_seed.encode()).decode().rstrip("=")


SHA1_SEED = b32("12345678901234567890")
SHA256_SEED = b32("12345678901234567890123456789012")
SHA512_SEED = b32("1234567890123456789012345678901234567890123456789012345678901234")

#: (value, timestamp) — the RFC 6238 vectors, the three stored shapes, clamping, and non-OTPs.
CASES: list[tuple[str, int]] = [
    (f"otpauth://totp/RFC?secret={SHA1_SEED}", 59),
    (f"otpauth://totp/RFC?secret={SHA1_SEED}", 1111111109),
    (f"otpauth://totp/RFC?secret={SHA1_SEED}&digits=8", 1111111111),
    (f"otpauth://totp/RFC?secret={SHA1_SEED}&digits=8", 1234567890),
    (f"otpauth://totp/RFC?secret={SHA256_SEED}&algorithm=SHA256&digits=8", 59),
    (f"otpauth://totp/RFC?secret={SHA256_SEED}&algorithm=SHA256&digits=8", 1111111109),
    (f"otpauth://totp/RFC?secret={SHA512_SEED}&algorithm=SHA512&digits=8", 20000000000),
    (f"otpauth://totp/RFC?secret={SHA512_SEED}&algorithm=SHA512&digits=8", 1234567890),
    (SHA1_SEED, 59),
    (SHA1_SEED, 2000000000),
    # the shape the migration writes: an entry's own host in the label, an issuer, a well-known test secret
    ("otpauth://totp/console.example.com:max%40example.com"
     "?secret=JBSWY3DPEHPK3PXP&issuer=console.example.com", 59),
    ("otpauth://totp/console.example.com:max%40example.com"
     "?secret=JBSWY3DPEHPK3PXP&issuer=console.example.com&period=60", 1111111109),
    # out-of-range parameters are clamped, never fatal
    (f"otpauth://totp/RFC?secret={SHA1_SEED}&digits=3&period=1", 59),
    (f"otpauth://totp/RFC?secret={SHA1_SEED}&digits=12", 59),
    (f"otpauth://totp/RFC?secret={SHA1_SEED}&algorithm=SHA999", 59),
    # a code someone pasted by hand: shown as stored, never regenerated
    ("654321", 59),
    ("1234567890", 59),
    # not an OTP at all
    ("—", 59),
    ("", 59),
    ("recovery codes are in the note below", 59),
    ("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP", 59),
]

#: Bodies the parser must read exactly like the desktop does.
BODIES: list[str] = [
    "# console.example.com\n\nسایت: console.example.com | دسته: سرور و زیرساخت\n\n"
    "نام کاربری: max@example.com\nگذرواژه: sample-pass\n"
    "آدرس: https://console.example.com/login\n"
    f"کد یکبارمصرف (otp): otpauth://totp/console.example.com?secret={SHA1_SEED}&issuer=console\n"
    "برچسب‌ها: آزمون\n",
    "# کارت\n\nسایت: bank.example | دسته: بانک و پرداخت\n\nشماره کارت: 6037991234567890\n",
    "# یادداشت\n\nمتن آزاد بدون فیلد\n\n## بخش دوم\n\nگذرواژه: این خط نباید خوانده شود\n",
    "# خالی\n\nنام کاربری: —\nگذرواژه: —\nکد یکبارمصرف (otp): —\n",
    "# بدون فیلد\n\nفقط متن\n",
]


def code_row(value: str, at: int) -> dict:
    """One expected row, straight from the desktop implementation."""
    generated = totp.value_to_code(value, at)
    row: dict = {"value": value, "at": at, "code": None, "live": False, "display": None,
                 "kind": None, "remaining": None, "period": None, "digits": None,
                 "algorithm": None, "issuer": None, "label": None}
    if generated is None:
        return row
    row.update(
        code=generated.code,
        live=generated.live,
        display=generated.display,
        kind=generated.kind,
        remaining=generated.remaining,
        period=generated.period,
        digits=generated.digits,
        algorithm=generated.algorithm,
        issuer=generated.issuer,
        label=generated.label,
    )
    return row


def body_row(body: str) -> dict:
    """One parser row, straight from the desktop implementation."""
    parsed = parse_body(body)
    return {
        "body": body,
        "username": parsed["username"],
        "password": parsed["password"],
        "otp": parsed["otp"],
        "site": parsed["site"],
        "category": parsed["category"],
        "url": parsed["url"],
        "has_otp": parsed["has_otp"],
        "has_password": parsed["has_password"],
    }


def main() -> int:
    payload = {
        "note": "generated by the desktop implementation (vault.core.totp / credentials.parse_body)"
                " — regenerate with tools/make_totp_fixture.py",
        "codes": [code_row(value, at) for value, at in CASES],
        "bodies": [body_row(body) for body in BODIES],
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    live = sum(1 for row in payload["codes"] if row["code"] is not None)
    print(f"wrote {OUT}")
    print(f"  {len(payload['codes'])} code rows ({live} readable), {len(payload['bodies'])} body rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
