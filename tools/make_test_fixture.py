#!/usr/bin/env python3
"""Build the JVM test fixture for the Android reader.

The fixture is produced by the *desktop* implementation (`/data/Codes/secure-vault/src`), so the
Kotlin tests are checked against the real producer rather than against values I typed by hand.

The single deviation is the KDF: the vault is created with `pbkdf2-sha512` instead of `argon2id`
(argv `--kdf argon2id` keeps argon2), so a plain JVM unit test — which has no native argon2 —
can derive the same master key. Everything else (blob format, HKDF, AES-GCM, the encrypted
content store, meta.sqlite) is byte-for-byte the desktop format.

Usage (from the repo root):
    PYTHONPATH=/data/Codes/secure-vault/src /data/Codes/secure-vault/.venv/bin/python \
        tools/make_test_fixture.py

Writes `app/src/test/resources/fixture/`.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import shutil
import sys
import tempfile
from pathlib import Path

PASSWORD = "correct horse battery staple"

NOTES: list[tuple[str, str, str]] = [
    (
        "یادداشت‌ها/سفر.md",
        "normal",
        "# سفر به شمال\n\n"
        "سه روز با گلزار رفتیم. هوای بارانی و جادهٔ چالوس.\n\n"
        "## خریدها\n\n"
        "- چادر\n"
        "- چراغ قوه\n\n"
        "1. بلیت\n"
        "2. سوغاتی\n\n"
        "> یادم باشد یخساز را هم بیاوریم.\n\n"
        "```bash\nssh -p 5233 najm@example\n```\n\n"
        "    نقشه: /data/Downloads/map.png\n\n"
        "| روز | جا |\n|---|---|\n| یک | رامسر |\n",
    ),
    (
        "برنامه‌نویسی/استفاده از NFS.md",
        "normal",
        "برای اشتراک فایل:\n\n"
        "    1. روی سرور:\n\n"
        "    ```bash\n    apt install nfs-kernel-server\n    ```\n\n"
        "    2. روی کلاینت:\n\n"
        "    ```bash\n    mount -t nfs host:/srv/nfs /mnt\n    ```\n\n"
        "متن لاتین stays left to right.\n",
    ),
    (
        "کار/رمز نمونه.md",
        "secretfile",
        "کارت بلوبانک\nشماره: 0000-0000-0000-0000\nرمز دوم: 9999\n",
    ),
]

TAGS = {
    "یادداشت‌ها/سفر.md": ["سفر", "گلزار"],
    "برنامه‌نویسی/استفاده از NFS.md": ["نصب", "سرور"],
}

FOLDER_NOTES = {
    "": "**گنجینه** — نقشهٔ ریشه.\n\n- یادداشت‌ها\n- برنامه‌نویسی\n- کار\n",
    "یادداشت‌ها": "یادداشت‌های شخصی روزانه.\n\nکی استفاده شود: برای مرور سفرها و رخدادها.\n",
    "برنامه‌نویسی": "فن‌آوری‌ها و دستورها.\n",
}

FILE_NOTES = {
    "یادداشت‌ها/سفر.md": "خلاصهٔ سفر شمال — برای مرور خریدها.",
    "برنامه‌نویسی/استفاده از NFS.md": "روش سوار کردن NFS روی کلاینت و سرور.",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="app/src/test/resources/fixture")
    parser.add_argument("--kdf", default="pbkdf2-sha512", choices=["pbkdf2-sha512", "argon2id"])
    parser.add_argument("--source", default="/data/Codes/secure-vault/src")
    args = parser.parse_args()

    sys.path.insert(0, args.source)
    from vault.core import crypto as crypto_mod  # noqa: E402

    if args.kdf == "pbkdf2-sha512":
        # force the fallback KDF so a plain JVM test can reproduce the key
        crypto_mod._HAVE_ARGON2 = False

    from vault.core.session import VaultSession  # noqa: E402

    out_dir = Path(args.out)
    vault_out = out_dir / "vault"
    if vault_out.exists():
        shutil.rmtree(vault_out)

    tmp_root = Path(tempfile.mkdtemp(prefix="sv-android-fixture-"))
    home = tmp_root / "vault"
    # Sync stays disabled while the fixture is being written: a vault with sync enabled refuses
    # writes until the sync lock is settled. The real settings block is patched in below, after
    # the content is complete — exactly the shape a synced desktop vault has.
    session = VaultSession.create(home, PASSWORD)

    for path, sensitivity, text in NOTES:
        session.write_file(path, text.encode("utf-8"), sensitivity=sensitivity)
    for path, tags in TAGS.items():
        session.index.set_tags(path, tags)
    for folder, note in FOLDER_NOTES.items():
        session.store.set_folder_note(folder, note)
    for path, note in FILE_NOTES.items():
        row = session.index.get_file(path)
        session.store.set_file_note(int(row["id"]), note)
    session.flush()

    # Fold the finished vault's settings into the metadata, mirroring a real synced vault's
    # `.vault-meta.json` (same keys and nesting as the desktop writes).
    close = getattr(session, "close", None) or getattr(session, "lock", None)
    if close is not None:
        close()
    meta_path = home / ".vault-meta.json"
    meta = json.loads(meta_path.read_text("utf-8"))
    meta["settings"] = {
        "plain_threshold_bytes": 10485760,
        "auto_lock_seconds": 0,
        "default_sensitivity": "normal",
        "semantic": {"enabled": False},
        "sync": {
            "enabled": True,
            "bucket": "test-bucket",
            "prefix": "sync",
            "endpoint": "https://s3.example.com",
            "region": "test-region-1",
        },
        "language": "fa",
    }
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", "utf-8")

    expected: dict = {
        "password": PASSWORD,
        "kdf_algo": args.kdf,
        "files": [],
        "folder_notes": FOLDER_NOTES,
        "file_notes": FILE_NOTES,
        "tags": TAGS,
        "sigv4": sigv4_reference(),
    }

    for path, sensitivity, text in NOTES:
        row = session.index.get_file(path)
        expected["files"].append(
            {
                "path": path,
                "blob_id": row["blob_id"],
                "sensitivity": sensitivity,
                "size": len(text.encode("utf-8")),
                "text": text,
                "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "blob_sha256": hashlib.sha256((home / "files" / row["blob_id"][:2] / f"{row['blob_id']}.enc").read_bytes()).hexdigest(),
            }
        )
    expected["tags"]["_note"] = "tags are attached to file rows"

    session.close()

    # copy the produced vault home into the fixture (metadata, store, blobs)
    vault_out.mkdir(parents=True, exist_ok=True)
    for name in (".vault-meta.json", "meta.sqlite", "secure.store"):
        shutil.copy2(home / name, vault_out / name)
    shutil.copytree(home / "files", vault_out / "files")

    (out_dir / "expected.json").write_text(
        json.dumps(expected, ensure_ascii=False, indent=1), encoding="utf-8"
    )

    meta = json.loads((vault_out / ".vault-meta.json").read_text(encoding="utf-8"))
    print("fixture written to", out_dir)
    print("kdf:", meta["kdf"]["algo"], "iterations:", meta["kdf"]["iterations"])
    print("blobs:", sum(1 for _ in (vault_out / "files").rglob("*.enc")))
    print("store bytes:", (vault_out / "secure.store").stat().st_size)
    shutil.rmtree(tmp_root, ignore_errors=True)
    return 0


def sigv4_reference() -> dict:
    """Independent (Python) reference values for the SigV4 test."""
    secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    region = "s3.ir-thr-at1"
    datestamp = "20260930"
    amz_date = "20260930T120000Z"
    access = "AKIAIOSFODNN7EXAMPLE"
    method = "GET"
    key = "sync/files/0a/0a1b2c3d.enc"
    path = "/majid-secure-vault/" + key
    query = "list-type=2&prefix=" + quote("sync/")
    payload_hash = hashlib.sha256(b"").hexdigest()
    canonical_headers = (
        f"host:s3.ir-thr-at1.arvanstorage.ir\n"
        f"x-amz-content-sha256:{payload_hash}\n"
        f"x-amz-date:{amz_date}\n"
    )
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join(
        [method, path, query, canonical_headers, signed_headers, payload_hash]
    )
    scope = f"{datestamp}/{region}/s3/aws4_request"
    string_to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amz_date,
            scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )

    def sign(key_bytes: bytes, message: str) -> bytes:
        return hmac.new(key_bytes, message.encode("utf-8"), hashlib.sha256).digest()

    k_date = sign(("AWS4" + secret).encode("utf-8"), datestamp)
    k_region = sign(k_date, region)
    k_service = sign(k_region, "s3")
    k_signing = sign(k_service, "aws4_request")
    signature = hmac.new(k_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "secret": secret,
        "access": access,
        "region": region,
        "datestamp": datestamp,
        "amz_date": amz_date,
        "method": method,
        "path": path,
        "query": query,
        "payload_hash": payload_hash,
        "canonical_request": canonical_request,
        "string_to_sign": string_to_sign,
        "scope": scope,
        "signing_key_hex": k_signing.hex(),
        "signature": signature,
        "authorization": (
            f"AWS4-HMAC-SHA256 Credential={access}/{scope}, "
            f"SignedHeaders={signed_headers}, Signature={signature}"
        ),
    }


def quote(value: str) -> str:
    from urllib.parse import quote as _q

    return _q(value, safe="-_.~")


if __name__ == "__main__":
    raise SystemExit(main())
