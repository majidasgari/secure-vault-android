# TASK_SPEC — Secure Vault برای اندروید (نسخهٔ فقط‌خواندنی)

وضعیت: پیاده‌سازی‌شده در همین مخزن.

## ۱. خواسته (صورت مسئله)

نسخهٔ اندروید Secure Vault با سه قید:

1. **سینک با S3** — همان باکتی که نسخهٔ دسکتاپ در آن آینه می‌گیرد.
2. **فقط پیمایش و خواندن یادداشت‌ها** — هیچ نوشتنی، نه در والت و نه در باکت.
3. **جست‌وجوی لفظی** — جست‌وجوی رشتهٔ عین (substring) روی متن یادداشت‌ها، نه جست‌وجوی توکنی/معنایی.

## ۲. قراردادهای نسخهٔ دسکتاپ که این برنامه باید رعایت کند

منبع: مخزن کلاینت دسکتاپ Secure Vault (`docs/ARCHITECTURE.md`، `docs/SECURITY.md`، `docs/SYNC.md`،
`src/vault/core/crypto.py`، `src/vault/core/sync.py`).

### چیدمان خانهٔ والت

```
<vault home>/
├── .vault-meta.json      متن‌آشکار: vault_id، پارامترهای KDF + salt، canary، settings.sync
├── meta.sqlite           متن‌آشکار: files / tags / file_tags / file_versions / access_log / kv
├── secure.store          رمزشده؛ متن‌آشکارش یک SQLite کوچک است (folder_notes، file_notes، fts_content)
└── files/<aa>/<blob>.enc  AES-256-GCM (فایل‌های > 10MB متن‌آشکار ذخیره می‌شوند)
```

### کلید و رمزنگاری

| مرحله | الگوریتم |
|---|---|
| کلید اصلی | Argon2id (t=3, m=262144 KiB, p=4, hash_len=32, v=19) یا PBKDF2-HMAC-SHA512 (600k) |
| تأیید گذرواژه | رمزگشایی `canary` با `blob_id="canary"` و `sensitivity="normal"` ⇒ `b"secure-vault"` |
| کلید هر فایل | `HKDF-SHA256(ikm=master, salt=blob_salt, info=b"secure-vault/file/"+blob_id, len=32)` |
| بدنه | `AES-256-GCM(nonce, payload, aad= b"sv/"+blob_id+"/"+sensitivity)` |
| سرآیند ۳۶ بایتی | `SVLT` + نسخه(۱) + kdf_id + flags(bit0=plain) + reserved + salt(16) + nonce(12) |
| بلاب plain | payload بدون AEAD؛ صحت سرآیند با `HMAC-SHA256(master, header[0:8]+nonce+aad)[:16]` در فیلد salt |
| `secure.store` | همان قالب بلاب با `blob_id="secure.store"` و `sensitivity="normal"` |

### سینک (سوی خواندن)

* کلید شیء = `prefix + "/" + مسیر نسبی در خانهٔ والت`.
* مسیرهای هرگز سینک‌نشدنی: `*.db`, `*.dec`, `store.*`, `*-wal`, `*-shm`, `*.tmp`, `.DS_Store`, پوشه‌های `semantic` و `cache`, و خودِ `.secure-vault.lock`.
* قفل نوشتن (`.secure-vault.lock`) هرگز نباید توسط این کلاینت گرفته یا آزاد شود.
* `meta.sqlite` قبل از آپلود توسط دسکتاپ checkpoint می‌شود؛ فایل‌های کنار آن (`-wal`/`-shm`) هرگز سینک نمی‌شوند.
* بلاب‌ها تغییرناپذیرند (هر ذخیره یک بلاب تازه) ⇒ خواندن هم‌زمان با نوشتن دسکتاپ بی‌خطر است.

### سیاست سطح دسترسی

| سطح | پیمایش | خواندن محتوا | در جست‌وجوی متنی |
|---|---|---|---|
| `normal` | ✅ | ✅ | ✅ |
| `secret` | ✅ | فقط با تأیید صریح، به‌صورت متن خام | ❌ |
| `secretfile` | ✅ | فقط با تأیید صریح، به‌صورت متن خام | ❌ |

## ۳. دامنهٔ پیاده‌سازی

* **داخل دامنه:** S3 pull (لیست/دریافت)، رمزگشایی کامل، پیمایش پوشه‌ها + دفترچهٔ پوشه‌ها، خواندن و نمایش
  markdown با قاعدهٔ RTL مکس، رندر تصاویر پیوست (در صورت دریافت)، جست‌وجوی لفظی با نرمال‌سازی فارسی،
  دریافت به‌تقاضا + پیش‌دریافت، قفل خودکار، ذخیرهٔ کلیدهای S3 در Keystore.
* **خارج از دامنه:** هر عملیات نوشتن (ویرایش، ساخت، حذف، تغییر سطح، برچسب‌گذاری)، ایندکس معنایی،
  تاریخچهٔ نسخه‌ها، اشتراک‌گذاری لینک، MCP، پنل لاگ/تنظیمات سرور.

## ۴. معماری

```
app/src/main/java/ir/maxv/securevault/
├── core/      Kotlin خالص، بدون وابستگی اندروید (قابل تست روی JVM)
│   ├── Crypto.kt   HKDF-SHA256، HMAC، PBKDF2-SHA512، AES-256-GCM
│   ├── Blob.kt     قالب بلاب، کلید هر فایل، AAD، canary، رمزگشایی secure.store
│   ├── Kdf.kt      KdfParams + رابط Argon2 + KeyDerivation + VaultMeta
│   ├── S3.kt       SigV4 + کلاینت فقط‌خواندنی (get/head/list/download)
│   ├── Sync.kt     SyncPlanner (سه‌طرفه اما فقط سمت خواندن) + SyncState
│   ├── Search.kt   نرمال‌سازی فارسی + matcher رشتهٔ عین + snippet
│   ├── Markdown.kt پارسر بلوکی + inline
│   └── Paths.kt    مسیرها و قواعد استثنا
├── data/      لایهٔ اندروید
│   ├── Argon2Native.kt  argon2kt (کتابخانهٔ نیتیو Argon2id)
│   ├── KeystoreBox.kt   مهر و موم کلیدهای S3 با Android Keystore
│   ├── LocalMirror.kt   آینهٔ محلی با همان چیدمان خانهٔ والت + state/settings
│   ├── VaultIndex.kt    meta.sqlite + یادداشت‌ها (SQLite اندروید)
│   ├── VaultSession.kt  والت باز: رمزگشایی، خواندن، جست‌وجو، کش رمزگشایی‌شده در حافظه
│   ├── SyncEngine.kt    همگام‌سازی pull + دریافت به‌تقاضا/دسته‌ای
│   └── VaultRepository.kt  ارکستراسیون + StateFlow
└── ui/        Compose: Setup / Unlock / Folder / Note / Search / Settings
```

اصل طراحی: **منطق حساس در `core/` بدون هیچ import اندرویدی** تا با تست JVM واقعاً اجرا شود؛ لایهٔ
اندروید فقط I/O و UI است.

## ۵. معیار پذیرش

1. والت ساخته‌شده با نسخهٔ دسکتاپ روی گوشی باز می‌شود (canary درست) و گذرواژهٔ غلط رد می‌شود.
2. متن هر یادداشت **بایت‌به‌بایت** همان چیزی است که دسکتاپ نوشته (`sha256` یکسان).
3. جست‌وجو زیررشتهٔ عین را پیدا می‌کند (مثل `یخسار` در `یخسارها`) و گونه‌های حرف فارسی را یکی می‌گیرد.
4. باکت هیچ‌وقت نوشته نمی‌شود: نه PUT، نه DELETE، نه قفل.
5. `secret`/`secretfile` تا تأیید صریح خوانده نمی‌شوند و در جست‌وجوی متنی نمی‌آیند.
6. خروجی: APK امضاشدهٔ release.

## ۶. راستی‌آزمایی

| لایه | روش | وضعیت |
|---|---|---|
| قالب بلاب/KDF/HKDF/AAD/canary/`secure.store` | fixture تولیدشده توسط خود پایتون دسکتاپ + تست JVM | `CryptoFixtureTest` |
| SigV4 | مقادیر مرجع تولیدشده مستقل با پایتون (`tools/make_test_fixture.py`) | `S3AndSyncTest` |
| کلاینت S3 واقعی | تست زندهٔ env-gated روی همان باکت (فقط list/get) | `S3LiveTest` |
| جست‌وجوی لفظی و نرمال‌سازی | تست واحد + متن fixture | `SearchTest` |
| پارسر markdown | تست واحد (fence تورفته، fence بی‌پایان، جدول، نقل‌قول، تصویر) | `MarkdownTest` |
| مسیرها/استثناها/برنامه‌ریز سینک | تست واحد | `S3AndSyncTest` |
| مسیر Argon2id | روی دستگاه: تأیید canary پس از استخراج کلید | دستی (تست روی گوشی) |
| UI | بدون emulator روی این ماشین قابل اجرا نیست | دستی (تست روی گوشی) |
