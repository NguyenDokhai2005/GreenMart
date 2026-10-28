# Security Checklist ΓÇô GreenMart (DT03)

**Phi├¬n bß║ún:** 1.0  
**Ng├áy soß║ín:** 27/10/2026  
**Ng╞░ß╗¥i soß║ín:** SV08 ΓÇô QA/Security (Huß╗│nh Ph├║c ─Éß║ít)  
**Tham chiß║┐u:** NFR-05, NFR-06, NFR-07, AUTH-01..04

---

## H╞░ß╗¢ng dß║½n sß╗¡ dß╗Ñng

Chß║íy checklist n├áy ß╗ƒ tuß║ºn 3 sau khi hß╗ç thß╗æng t├¡ch hß╗úp ─æß║ºy ─æß╗º. Mß╗ùi mß╗Ñc ─æ├ính dß║Ñu:
- Γ£à ─Éß║ít
- Γ¥î Kh├┤ng ─æß║ít ΓåÆ mß╗ƒ Issue ngay
- ΓÅ¡ Bß╗Å qua k├¿m l├╜ do

---

## 1. X├íc thß╗▒c v├á ph├ón quyß╗ün (NFR-05)

### 1.1 JWT

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| A-01 | Access token hß║┐t hß║ín sau 15 ph├║t | Decode JWT kiß╗âm tra `exp`, chß╗¥ hß║┐t hß║ín gß╗ìi API | HTTP 401 | |
| A-02 | Refresh token c├▓n hiß╗çu lß╗▒c 7 ng├áy | Kiß╗âm tra `exp` trong refresh token | Thß╗¥i hß║ín ─æ├║ng 7 ng├áy | |
| A-03 | JWT giß║ú mß║ío vß╗¢i secret sai | Tß║ío token vß╗¢i secret t├╣y ├╜, gß╗ìi API | HTTP 401 | |
| A-04 | JWT algorithm `none` | Gß╗¡i token `alg: none` | HTTP 401 | |
| A-05 | JWT thiß║┐u claim `role` hoß║╖c `branchIds` | Tß║ío token kh├┤ng c├│ claim cß║ºn thiß║┐t | HTTP 401 hoß║╖c 403 | |

### 1.2 Mß║¡t khß║⌐u

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| P-01 | Mß║¡t khß║⌐u l╞░u dß║íng BCrypt | `SELECT password_hash FROM app_user LIMIT 5` | Bß║»t ─æß║ºu `$2a$` hoß║╖c `$2b$` | |
| P-02 | Kh├┤ng c├│ mß║¡t khß║⌐u plain text trong DB | Xem trß╗▒c tiß║┐p bß║úng `app_user` | Kh├┤ng c├│ cß╗Öt n├áo chß╗⌐a mß║¡t khß║⌐u r├╡ | |
| P-03 | Brute force lockout sau 5 lß║ºn sai | Gß╗¡i 6 lß║ºn ─æ─âng nhß║¡p sai li├¬n tiß║┐p | T├ái khoß║ún kh├│a 15 ph├║t sau lß║ºn 5 | |
| P-04 | Th├┤ng b├ío lß╗ùi ─æ─âng nhß║¡p kh├┤ng lß╗Ö nguy├¬n nh├ón | ─É─âng nhß║¡p sai username vs sai password | C├╣ng mß╗Öt th├┤ng b├ío lß╗ùi chung | |

### 1.3 Ph├ón quyß╗ün (RBAC)

| # | Kiß╗âm tra | Endpoint | Role test | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|---|
| R-01 | Thu ng├ón CN1 kh├┤ng truy cß║¡p CN2 | `GET /api/v1/orders?branchId={CN2}` | CASHIER (CN1) | HTTP 403 | |
| R-02 | Thu ng├ón kh├┤ng sß╗¡a gi├í | `PUT /api/v1/products/{id}` (price) | CASHIER | HTTP 403 | |
| R-03 | Thß╗º kho kh├┤ng duyß╗çt phiß║┐u m├¼nh lß║¡p | Tß║ío rß╗ôi duyß╗çt c├╣ng t├ái khoß║ún | STOREKEEPER | HTTP 422 | |
| R-04 | Admin truy cß║¡p to├án chuß╗ùi | Bß║Ñt kß╗│ endpoint c├│ `branchId` | ADMIN | HTTP 200 | |
| R-05 | Owner truy cß║¡p to├án chuß╗ùi | `GET /api/v1/orders` mß╗ìi branch | OWNER | HTTP 200 | |
| R-06 | Truy cß║¡p trß╗▒c tiß║┐p URL kh├┤ng ─æß╗º quyß╗ün | `GET /api/v1/admin/users` | CASHIER | HTTP 403 | |
| R-07 | API kh├┤ng c├│ token | `GET /api/v1/orders` | Kh├┤ng c├│ header Authorization | HTTP 401 | |

---

## 2. Injection v├á dß╗» liß╗çu ─æß║ºu v├áo (NFR-06)

| # | Kiß╗âm tra | Payload | Endpoint v├¡ dß╗Ñ | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|---|
| I-01 | SQL Injection ΓÇô t├¼m kiß║┐m sß║ún phß║⌐m | `' OR 1=1--` | `GET /api/v1/products?search=...` | HTTP 400 hoß║╖c 200 vß╗¢i kß║┐t quß║ú trß╗æng, kh├┤ng lß╗Ö DB | |
| I-02 | SQL Injection ΓÇô t├¼m kh├ích h├áng | `'; DROP TABLE app_user--` | `GET /api/v1/customers?phone=...` | Kh├┤ng x├│a dß╗» liß╗çu, kh├┤ng lß╗ùi 500 | |
| I-03 | XSS trong t├¬n sß║ún phß║⌐m | `<script>alert(1)</script>` | `POST /api/v1/products` | L╞░u d╞░ß╗¢i dß║íng escaped hoß║╖c tß╗½ chß╗æi | |
| I-04 | Path traversal | `../../etc/passwd` | Bß║Ñt kß╗│ endpoint c├│ ID | HTTP 400 hoß║╖c 404 | |
| I-05 | Integer overflow gi├í b├ín | Gi├í = `9999999999999999` | `PUT /api/v1/products/{id}` | Validation lß╗ùi hoß║╖c bß╗ï cß║»t ─æ├║ng kiß╗âu | |
| I-06 | Sß╗æ l╞░ß╗úng ├óm trong ─æ╞ín | `quantity: -1` | `POST /api/v1/orders` | HTTP 400 | |
| I-07 | JSON injection / nested object | Th├¬m tr╞░ß╗¥ng ngo├ái spec | `POST /api/v1/orders` | Tr╞░ß╗¥ng ngo├ái bß╗ï bß╗Å qua, kh├┤ng lß╗ùi | |

---

## 3. Lß╗Ö th├┤ng tin (NFR-06)

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| E-01 | Stack trace kh├┤ng xuß║Ñt hiß╗çn trong response lß╗ùi | Gß╗¡i request sai format cß╗æ ├╜ g├óy exception | Response chß╗ë c├│ `code`, `message`, `traceId` | |
| E-02 | Kh├┤ng lß╗Ö version framework | Xem headers response | Kh├┤ng c├│ `X-Powered-By`, `Server` version cß╗Ñ thß╗â | |
| E-03 | Log kh├┤ng chß╗⌐a secret | Xem application log staging | Kh├┤ng c├│ JWT, password, API key trong log | |
| E-04 | `traceId` trong log khß╗¢p vß╗¢i response | G├óy lß╗ùi, lß║Ñy `traceId` tß╗½ response, t├¼m trong log | Log c├│ ─æ├║ng correlation ID | |

---

## 4. CORS (NFR-06)

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| C-01 | Origin kh├┤ng cho ph├⌐p bß╗ï chß║╖n | Gß╗¡i request vß╗¢i `Origin: https://evil.com` | Kh├┤ng c├│ `Access-Control-Allow-Origin: *` hoß║╖c kh├┤ng trß║ú header CORS | |
| C-02 | Origin frontend hß╗úp lß╗ç ─æ╞░ß╗úc chß║Ñp nhß║¡n | Request tß╗½ origin ─æ├║ng | `Access-Control-Allow-Origin` ─æ├║ng domain | |
| C-03 | Preflight OPTIONS trß║ú ─æ├║ng headers | `OPTIONS` request | HTTP 200/204, headers CORS ─æ├║ng | |

---

## 5. Secrets v├á CI (NFR-07)

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| G-01 | Kh├┤ng c├│ secrets trong repo | `gitleaks detect --source .` | `0 leaks found` | |
| G-02 | `.env` kh├┤ng bß╗ï commit | `git log --all -- .env` | Kh├┤ng c├│ commit chß╗⌐a `.env` | |
| G-03 | Biß║┐n m├┤i tr╞░ß╗¥ng qua `.env.example` | Xem `.env.example` | Chß╗ë c├│ key, kh├┤ng c├│ value thß║¡t | |
| G-04 | Gitleaks chß║íy trong CI | Xem `.github/workflows/` | C├│ step gitleaks khi PR | |
| G-05 | Webhook secret trong DB ─æ╞░ß╗úc m├ú h├│a | `SELECT webhook_secret FROM sales_channel` | Kh├┤ng l╞░u plain text | |

---

## 6. T├¡ch hß╗úp k├¬nh b├ín (SYNC-02)

| # | Kiß╗âm tra | C├ích thß╗▒c hiß╗çn | Kß║┐t quß║ú mong ─æß╗úi | Kß║┐t quß║ú |
|---|---|---|---|---|
| W-01 | Webhook kh├┤ng c├│ chß╗» k├╜ | Gß╗¡i POST kh├┤ng c├│ header `X-Signature` | HTTP 401 | |
| W-02 | Webhook chß╗» k├╜ sai | Gß╗¡i POST vß╗¢i HMAC sai secret | HTTP 401 | |
| W-03 | Webhook replay attack | Gß╗¡i lß║íi ─æ├║ng request ─æ├ú xß╗¡ l├╜ | Trß║ú ─æ╞ín c┼⌐, kh├┤ng tß║ío mß╗¢i (idempotent) | |
| W-04 | Webhook payload bß╗ï sß╗¡a | Sß╗¡a body nh╞░ng giß╗» signature c┼⌐ | HTTP 401 | |

---

## 7. Kß║┐t luß║¡n

Ho├án th├ánh checklist n├áy ΓåÆ cß║¡p nhß║¡t kß║┐t quß║ú v├áo `docs/testing/test-report.md` v├á mß╗ƒ Issue cho mß╗ìi mß╗Ñc Γ¥î.

Mß╗ìi lß╗ùi bß║úo mß║¡t mß╗⌐c **nghi├¬m trß╗ìng** phß║úi ─æ╞░ß╗úc fix tr╞░ß╗¢c khi demo cuß╗æi kß╗│.
