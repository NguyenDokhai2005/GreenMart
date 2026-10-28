# Kß║┐ hoß║ích kiß╗âm thß╗¡ ΓÇô GreenMart (DT03)

**Phi├¬n bß║ún:** 1.0  
**Ng├áy soß║ín:** 27/10/2026  
**Ng╞░ß╗¥i soß║ín:** SV08 ΓÇô QA/Security (Huß╗│nh Ph├║c ─Éß║ít)  
**Trß║íng th├íi:** Tuß║ºn 1 ΓÇô Bß║ún nh├íp khß╗ƒi tß║ío

---

## 1. Mß╗Ñc ti├¬u kiß╗âm thß╗¡

─Éß║úm bß║úo hß╗ç thß╗æng GreenMart ─æ├íp ß╗⌐ng to├án bß╗Ö 6 ti├¬u ch├¡ nghiß╗çm thu (AC-01..AC-06), 14 y├¬u cß║ºu phi chß╗⌐c n─âng (NFR-01..14) v├á kh├┤ng c├│ lß╗ùi thuß╗Öc 19 quy tß║»c nghiß╗çp vß╗Ñ (BR-01..19). Trß╗ìng t├óm ─æß║╖c biß╗çt v├áo:

- **T├¡nh nguy├¬n tß╗¡ cß╗ºa tß╗ôn kho** ΓÇö kh├┤ng tß╗ôn ├óm trong mß╗ìi t├¼nh huß╗æng ─æß╗ông thß╗¥i (AC-02, INV-07).
- **Ph├ón quyß╗ün v├á bß║úo mß║¡t** ΓÇö mß╗ìi API kiß╗âm tra vai tr├▓ + chi nh├ính ß╗ƒ backend (AC-04, NFR-05/06).
- **─Éß╗ông bß╗Ö hai chiß╗üu** ΓÇö ─æ╞ín online v├á tß╗ôn kho ─æß║┐n k├¬nh ─æ├║ng v├á kh├┤ng mß║Ñt dß╗» liß╗çu (AC-03).
- **Chß║Ñt l╞░ß╗úng m├ú** ΓÇö unit test coverage ΓëÑ 70 % tr├¬n module sales v├á inventory (NFR-11).

---

## 2. Phß║ím vi

### 2.1 Trong phß║ím vi kiß╗âm thß╗¡

| Khu vß╗▒c | Loß║íi test | C├┤ng cß╗Ñ |
|---|---|---|
| API REST (/api/v1) | API test theo luß╗ông nghiß╗çp vß╗Ñ | Postman + Newman |
| Tß╗ôn kho ─æß╗ông thß╗¥i | Load test / concurrency test | k6 |
| Ph├ón quyß╗ün (RBAC) | Negative test ΓÇô gß╗ìi sai vai tr├▓/chi nh├ính | Postman |
| Bß║úo mß║¡t c╞í bß║ún | OWASP Top 10 check c╞í bß║ún | Thß╗º c├┤ng + c├┤ng cß╗Ñ |
| Luß╗ông E2E giao diß╗çn | B├ín tß║íi quß║ºy, ─æ╞ín online | Playwright |
| Secrets trong repo | Qu├⌐t gitleaks | CI / gitleaks |
| Idempotency | Gß╗¡i tr├╣ng ─æ╞ín, idempotency-key | Postman |
| T├¡ch hß╗úp RabbitMQ | Tß║»t 5 ph├║t, kiß╗âm tra b├╣ sß╗▒ kiß╗çn | Test t├¡ch hß╗úp thß╗º c├┤ng |

### 2.2 Ngo├ái phß║ím vi

- Thanh to├ín thß║¡t (ng├ón h├áng, v├¡ ─æiß╗çn tß╗¡)
- Kß║┐t nß╗æi Shopee, Lazada, TikTok Shop thß║¡t
- Kiß╗âm thß╗¡ ß╗⌐ng dß╗Ñng di ─æß╗Öng

---

## 3. Chiß║┐n l╞░ß╗úc kiß╗âm thß╗¡

### 3.1 Ph├ón loß║íi

```
Unit test (SV02, SV03 viß║┐t) ΓöÇΓöÇΓû║ Integration test ΓöÇΓöÇΓû║ API test ΓöÇΓöÇΓû║ E2E / Load test
                                        Γåæ
                               SV08 phß╗Ñ tr├ích tß╗½ Integration trß╗ƒ l├¬n
```

- **Unit test**: SV02 (backend sales), SV03 (backend inventory) viß║┐t; SV08 review coverage report.
- **API test (Postman)**: SV08 viß║┐t, chß║íy trong CI vß╗¢i Newman.
- **Load test (k6)**: SV08 viß║┐t script, chß║íy thß╗º c├┤ng v├á CI.
- **E2E (Playwright)**: SV08 viß║┐t, chß║íy trong CI.
- **Security test**: SV08 thß╗▒c hiß╗çn theo checklist OWASP c╞í bß║ún ß╗ƒ tuß║ºn 3.

### 3.2 M├┤i tr╞░ß╗¥ng

| M├┤i tr╞░ß╗¥ng | M├┤ tß║ú | Ng╞░ß╗¥i quß║ún l├╜ |
|---|---|---|
| Local | Docker Compose tß╗½ng m├íy dev | Mß╗ùi th├ánh vi├¬n |
| Staging | Tß╗▒ cß║¡p nhß║¡t khi merge v├áo `develop` | SV07 |

Test tß╗▒ ─æß╗Öng chß║íy tr├¬n staging. Load test chß║íy tr├¬n local tr╞░ß╗¢c khi demo ─æß╗â tr├ính ß║únh h╞░ß╗ƒng m├┤i tr╞░ß╗¥ng.

### 3.3 Dß╗» liß╗çu test

- Sß╗¡ dß╗Ñng seed script (SV06) tß║ío 3 chi nh├ính, 1.000 sß║ún phß║⌐m, 10.000 ─æ╞ín lß╗ïch sß╗¡.
- T├ái khoß║ún demo 5 vai tr├▓ ├ù 3 chi nh├ính (tß╗½ seed script).
- Fixture JSON cho Postman v├á Playwright tß║ío ß╗ƒ `tests/api/fixtures/`.

---

## 4. Test cases theo User Story

### US-01 ┬╖ B├ín tß║íi quß║ºy (ORD-01, PAY-01)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-POS-01 | B├ín h├áng b├¼nh th╞░ß╗¥ng ΓÇô tiß╗ün mß║╖t | Sß║ún phß║⌐m X c├│ khß║ú dß╗Ñng 10, giß╗Å 2 m├│n | Thanh to├ín tiß╗ün mß║╖t, ho├án tß║Ñt | ─É╞ín COMPLETED, khß║ú dß╗Ñng c├▓n 8, sß╗ò kho SALE -2 | Must |
| TC-POS-02 | T├¡nh khuyß║┐n mß║íi ─æ├║ng | Giß╗Å 238.000 ─æ vß╗¢i KM-GAO v├á KM-10 | T├¡nh tiß╗ün | Tß╗òng phß║úi trß║ú 200.700 ─æ | Must |
| TC-POS-03 | Idempotency ΓÇô bß║Ñm ─æ├║p | C├╣ng Idempotency-Key | Gß╗¡i 2 lß║ºn | Chß╗ë 1 ─æ╞ín ─æ╞░ß╗úc tß║ío | Must |
| TC-POS-04 | Tß╗ôn bß╗ï lß║Ñy ─æ├║ng l├║c ho├án tß║Ñt | Khß║ú dß╗Ñng 1, ─æ╞ín kh├íc lß║Ñy tr╞░ß╗¢c | Ho├án tß║Ñt | Trß║ú 409 INSUFFICIENT_STOCK, giß╗Å giß╗» nguy├¬n | Must |
| TC-POS-05 | Thanh to├ín thß║╗ thß║Ñt bß║íi | Giß║ú lß║¡p thß║╗ thß║Ñt bß║íi | Bß║Ñm Ho├án tß║Ñt | Kh├┤ng tß║ío ─æ╞ín, cho chß╗ìn lß║íi | Must |
| TC-POS-06 | Thanh to├ín QR qu├í 60 gi├óy | Giß║ú lß║¡p QR timeout | Chß╗¥ 60 gi├óy | Kh├┤ng tß║ío ─æ╞ín | Must |
| TC-POS-07 | Tiß╗ün ─æ╞░a < tß╗òng | Tiß╗ün mß║╖t nhß╗Å h╞ín tß╗òng | Ho├án tß║Ñt | Bß╗ï chß║╖n, th├┤ng b├ío r├╡ | Must |
| TC-POS-08 | Sß║ún phß║⌐m kh├┤ng tß╗ôn tß║íi / ngß╗½ng b├ín | Qu├⌐t m├ú kh├┤ng c├│ | Qu├⌐t | B├ío lß╗ùi, kh├┤ng th├¬m d├▓ng | Must |

### US-02 ┬╖ Kh├┤ng b├ín v╞░ß╗út tß╗ôn (INV-07, AC-02)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-CONC-01 | 100 y├¬u cß║ºu ─æß╗ông thß╗¥i, tß╗ôn 5 | Khß║ú dß╗Ñng = 5, mß╗ùi y├¬u cß║ºu mua 1 | 100 request song song | ─É├║ng 5 th├ánh c├┤ng, 95 nhß║¡n 409, khß║ú dß╗Ñng = 0 | Must |
| TC-CONC-02 | ─É╞ín hß╗ùn hß╗úp POS + online | Tß╗ôn 5, 50 POS + 50 webhook ─æß╗ông thß╗¥i | Gß╗¡i ─æß╗ông thß╗¥i | Tß╗òng th├ánh c├┤ng Γëñ 5, kh├┤ng tß╗ôn ├óm | Must |
| TC-CONC-03 | Kh├┤ng deadlock ─æ╞ín nhiß╗üu d├▓ng | ─É╞ín A c├│ [P1,P2], ─æ╞ín B c├│ [P2,P1] | Gß╗¡i ─æß╗ông thß╗¥i | Kh├┤ng deadlock, cß║ú hai th├ánh c├┤ng nß║┐u ─æß╗º tß╗ôn | Must |
| TC-CONC-04 | CHECK constraint DB tß╗ôn ├óm | Trß╗▒c tiß║┐p update DB v╞░ß╗út tß╗ôn | SQL trß╗▒c tiß║┐p | DB tß╗½ chß╗æi vß╗¢i constraint violation | Must |

### US-03 ┬╖ Nhß║¡n ─æ╞ín online (ORD-02, SYNC-02)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-ONL-01 | ─Éß║╖t h├áng hß╗úp lß╗ç ΓÇô giß╗» chß╗ù | Khß║ú dß╗Ñng 10, ─æ╞ín 3 c├íi | Webhook hß╗úp lß╗ç | ─É╞ín RESERVED, reserved = 3, khß║ú dß╗Ñng = 7 | Must |
| TC-ONL-02 | Gß╗¡i tr├╣ng ─æ╞ín | ─É╞ín WEB-100 ─æ├ú nhß║¡n | Gß╗¡i lß║íi WEB-100 | Trß║ú ─æ╞ín c┼⌐, kh├┤ng giß╗» chß╗ù th├¬m | Must |
| TC-ONL-03 | Chß╗» k├╜ HMAC sai | Bß║Ñt kß╗│ ─æ╞ín n├áo | HMAC sai | HTTP 401, kh├┤ng ghi ─æ╞ín | Must |
| TC-ONL-04 | SKU ch╞░a ├ính xß║í | SKU k├¬nh ch╞░a c├│ ├ính xß║í nß╗Öi bß╗Ö | Webhook | ─É╞ín REJECTED, kh├┤ng giß╗» chß╗ù | Must |
| TC-ONL-05 | Ph├ón bß╗ò chi nh├ính mß║╖c ─æß╗ïnh ─æß╗º tß╗ôn | CN mß║╖c ─æß╗ïnh ─æß╗º h├áng | Webhook | Ph├ón bß╗ò ─æ├║ng CN mß║╖c ─æß╗ïnh | Must |
| TC-ONL-06 | Ph├ón bß╗ò CN kh├íc khi CN mß║╖c ─æß╗ïnh kh├┤ng ─æß╗º | CN mß║╖c ─æß╗ïnh thiß║┐u, CN2 ─æß╗º | Webhook | Ph├ón bß╗ò ─æ├║ng CN2 | Must |
| TC-ONL-07 | Mß╗ìi CN ─æß╗üu kh├┤ng ─æß╗º | Kh├┤ng CN n├áo ─æß╗º tß╗ôn | Webhook | ─É╞ín REJECTED l├╜ do OUT_OF_STOCK | Must |

### US-04 ┬╖ Hß║┐t hß║ín giß╗» chß╗ù (ORD-03)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-EXP-01 | ─É╞ín RESERVED qu├í 15 ph├║t | ─É╞ín RESERVED giß╗» 3, l├║c 10:00 | ─Éß║┐n 10:15+1 ph├║t | ─É╞ín EXPIRED, reserved giß║úm 3, k├¬nh nhß║¡n tß╗ôn mß╗¢i | Must |
| TC-EXP-02 | Thanh to├ín trong hß║ín | ─É╞ín RESERVED | Thanh to├ín tr╞░ß╗¢c 15 ph├║t | ─É╞ín CONFIRMED, giß╗» chß╗ù giß╗» nguy├¬n | Must |

### US-05 ┬╖ ─Éß╗ông bß╗Ö tß╗ôn ra k├¬nh (SYNC-03)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-SYNC-01 | Tß╗ôn ─æß║┐n k├¬nh sau giao dß╗ïch | Tß╗ôn thay ─æß╗òi | Commit giao dß╗ïch | K├¬nh nhß║¡n tß╗ôn mß╗¢i trong 1 ph├║t | Must |
| TC-SYNC-02 | RabbitMQ dß╗½ng 5 ph├║t | RabbitMQ ngß║»t | Nhiß╗üu giao dß╗ïch xß║úy ra | Sau khi RabbitMQ chß║íy lß║íi, mß╗ìi cß║¡p nhß║¡t ─æ╞░ß╗úc gß╗¡i b├╣ | Must |
| TC-SYNC-03 | Bß╗Å qua bß║ún version c┼⌐ | K├¬nh ─æ├ú c├│ version 5 | Nhß║¡n version 3 | K├¬nh bß╗Å qua, kh├┤ng cß║¡p nhß║¡t | Must |

### US-08 ┬╖ Ph├ón quyß╗ün theo chi nh├ính (AUTH-02, AC-04)

| ID | Ti├¬u ─æß╗ü | Given | When | Then | Mß╗⌐c ─æß╗Ö |
|---|---|---|---|---|---|
| TC-AUTH-01 | Thu ng├ón CN1 gß╗ìi API CN2 | Thu ng├ón g├ín CN1 | GET /orders?branchId=CN2 | HTTP 403 | Must |
| TC-AUTH-02 | Thu ng├ón CN1 tß║ío ─æ╞ín tß║íi CN2 | Thu ng├ón g├ín CN1 | POST /orders branchId=CN2 | HTTP 403 | Must |
| TC-AUTH-03 | Gß╗ìi API kh├┤ng c├│ token | Ch╞░a ─æ─âng nhß║¡p | Bß║Ñt kß╗│ protected endpoint | HTTP 401 | Must |
| TC-AUTH-04 | Token hß║┐t hß║ín | JWT hß║┐t hß║ín 15 ph├║t | Gß╗ìi API | HTTP 401 | Must |
| TC-AUTH-05 | Refresh token c├▓n hß║ín | Refresh token 7 ng├áy | POST /auth/refresh | Nhß║¡n access token mß╗¢i | Must |
| TC-AUTH-06 | ─É─âng nhß║¡p sai 5 lß║ºn | Sai mß║¡t khß║⌐u 5 lß║ºn li├¬n tiß║┐p | Lß║ºn thß╗⌐ 6 | T├ái khoß║ún bß╗ï kh├│a 15 ph├║t | Must |
| TC-AUTH-07 | Thu ng├ón kh├┤ng ─æß╗òi gi├í | Thu ng├ón CN1 | PUT /products/{id}/price | HTTP 403 | Must |
| TC-AUTH-08 | Owner truy cß║¡p to├án chuß╗ùi | Owner | GET orders mß╗ìi chi nh├ính | HTTP 200 | Must |
| TC-AUTH-09 | Nhß║¡t k├╜ ─æ─âng nhß║¡p thß║Ñt bß║íi | ─É─âng nhß║¡p sai | Sai mß║¡t khß║⌐u | audit_log c├│ bß║ún ghi | Must |
| TC-AUTH-10 | Ng╞░ß╗¥i lß║¡p phiß║┐u kh├┤ng tß╗▒ duyß╗çt | Thß╗º kho A lß║¡p phiß║┐u | A tß╗▒ duyß╗çt | HTTP 422 / lß╗ùi nghiß╗çp vß╗Ñ | Must |

---

## 5. Kiß╗âm thß╗¡ bß║úo mß║¡t (NFR-05, NFR-06, NFR-07)

### 5.1 Checklist OWASP c╞í bß║ún

| # | Hß║íng mß╗Ñc | C├ích kiß╗âm tra | Kß║┐t quß║ú mong ─æß╗úi |
|---|---|---|---|
| S-01 | SQL Injection | Truyß╗ün payload `' OR 1=1--` v├áo c├íc tr╞░ß╗¥ng t├¼m kiß║┐m | HTTP 400, kh├┤ng lß╗Ö dß╗» liß╗çu |
| S-02 | JWT giß║ú mß║ío | Gß╗¡i token tß╗▒ k├╜ vß╗¢i secret giß║ú | HTTP 401 |
| S-03 | JWT thiß║┐u chß╗» k├╜ | Gß╗¡i token `algorithm: none` | HTTP 401 |
| S-04 | Stack trace trong lß╗ùi | Gß╗¡i request sai format | Lß╗ùi trß║ú vß╗ü chß╗ë c├│ `code`, `message`, kh├┤ng lß╗Ö stack trace |
| S-05 | CORS | Request tß╗½ origin kh├┤ng cho ph├⌐p | CORS header chß║╖n |
| S-06 | Th├┤ng tin nhß║íy cß║úm trong log | Xem log staging | Kh├┤ng c├│ password, secret key trong log |
| S-07 | Secrets trong repo | Chß║íy `gitleaks detect` | 0 secrets found |
| S-08 | Mß║¡t khß║⌐u hashing | Xem trß╗▒c tiß║┐p DB | Tr╞░ß╗¥ng password_hash l├á BCrypt (bß║»t ─æß║ºu `$2`) |
| S-09 | HMAC webhook | Gß╗¡i ─æ╞ín vß╗¢i signature sai | HTTP 401, kh├┤ng ghi ─æ╞ín |
| S-10 | Brute force login | 5 lß║ºn sai li├¬n tiß║┐p | T├ái khoß║ún kh├│a 15 ph├║t |

### 5.2 Gitleaks CI

Cß║Ñu h├¼nh gitleaks chß║íy trong GitHub Actions khi mß╗ƒ PR v├áo `develop`.

---

## 6. Kiß╗âm thß╗¡ hiß╗çu n─âng (NFR-01, NFR-02)

### 6.1 Load test ΓÇô k6 (tests/load/)

| Script | Mß╗Ñc ti├¬u | Ng╞░ß╗íng ─æß║ít |
|---|---|---|
| `load-read-api.js` | API ─æß╗ìc vß╗¢i 50 VU ─æß╗ông thß╗¥i | p95 < 300 ms |
| `load-create-order.js` | API tß║ío ─æ╞ín vß╗¢i 50 VU ─æß╗ông thß╗¥i | p95 < 500 ms |
| `concurrency-stock.js` | 100 y├¬u cß║ºu ─æß╗ông thß╗¥i, tß╗ôn 5 | ─É├║ng 5 th├ánh c├┤ng, kh├┤ng tß╗ôn ├óm |

### 6.2 Integration test timing

| H├ánh vi | Ng╞░ß╗íng |
|---|---|
| Tß╗ôn mß╗¢i ─æß║┐n k├¬nh sau commit | < 1 ph├║t |
| ─É╞ín EXPIRED sau 15 ph├║t ch╞░a thanh to├ín | < 1 ph├║t sau mß╗æc 15 ph├║t |

---

## 7. Kß║┐ hoß║ích theo tuß║ºn

| Tuß║ºn | Viß╗çc l├ám | Deliverable |
|---|---|---|
| **1** (28/10 ΓÇô 03/11) | Viß║┐t test plan, tß║ío cß║Ñu tr├║c th╞░ mß╗Ñc tests, thiß║┐t lß║¡p Postman collection khung, viß║┐t checklist security, khß╗ƒi tß║ío k6 skeleton | `docs/testing/test-plan.md`, `tests/api/greenmart.postman_collection.json`, `tests/load/*.js` (skeleton), `docs/testing/security-checklist.md` |
| **2** (04/11 ΓÇô 10/11) | Viß║┐t Postman test cho AUTH, ORD, INV khi API sß║╡n s├áng; viß║┐t script k6 ─æß║ºy ─æß╗º; bß║»t ─æß║ºu Playwright skeleton | Collection ─æß║ºy ─æß╗º, k6 scripts, Playwright skeleton |
| **3** (11/11 ΓÇô 17/11) | Chß║íy full test, viß║┐t b├ío c├ío lß╗ùi, thß╗▒c hiß╗çn security checklist OWASP, viß║┐t ma trß║¡n truy vß║┐t | `docs/testing/test-report.md`, `docs/testing/traceability-matrix.md` |
| **4** (18/11 ΓÇô 24/11) | Regression test, chß║íy load test cuß╗æi, ho├án thiß╗çn b├ío c├ío kiß╗âm thß╗¡ cho demo | B├ío c├ío kiß╗âm thß╗¡ cuß╗æi |

---

## 8. Quy tr├¼nh b├ío lß╗ùi

1. Ph├ít hiß╗çn lß╗ùi ΓåÆ mß╗ƒ Issue d├╣ng template `bug.md`.
2. G├ín nh├ún `test`, g├ín milestone tuß║ºn, g├ín ng╞░ß╗¥i phß╗Ñ tr├ích module.
3. Ghi r├╡: m├┤ tß║ú, b╞░ß╗¢c t├íi hiß╗çn, kß║┐t quß║ú mong ─æß╗úi, kß║┐t quß║ú thß╗▒c tß║┐, m├┤i tr╞░ß╗¥ng, mß╗⌐c ─æß╗Ö (chß║╖n demo / nghi├¬m trß╗ìng / nhß╗Å).
4. Lß╗ùi mß╗⌐c "chß║╖n demo" ΓåÆ b├ío ngay qua daily hoß║╖c nhß║»n trß╗▒c tiß║┐p cho ng╞░ß╗¥i phß╗Ñ tr├ích.
5. Sau khi fix ΓåÆ SV08 verify v├á ─æ├│ng issue.

---

## 9. Definition of Done cho QA

- [ ] Mß╗ìi TC must-have trong kß║┐ hoß║ích n├áy ─æ├ú chß║íy v├á pass.
- [ ] Kh├┤ng c├▓n lß╗ùi mß╗⌐c "chß║╖n demo" ch╞░a xß╗¡ l├╜.
- [ ] Coverage report module `sales` v├á `inventory` ΓëÑ 70 %.
- [ ] Gitleaks CI xanh (kh├┤ng c├│ secrets trong repo).
- [ ] B├ío c├ío kiß╗âm thß╗¡ cß║¡p nhß║¡t trong `docs/testing/test-report.md`.
- [ ] Ma trß║¡n truy vß║┐t ho├án chß╗ënh trong `docs/testing/traceability-matrix.md`.

---

*T├ái liß╗çu n├áy sß║╜ ─æ╞░ß╗úc cß║¡p nhß║¡t sau mß╗ùi tuß║ºn khi c├│ th├¬m API sß║╡n s├áng ─æß╗â test.*
