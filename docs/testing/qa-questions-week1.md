# C├óu hß╗Åi & Giß║ú ─æß╗ïnh cß║ºn x├íc nhß║¡n ΓÇô QA Tuß║ºn 1

**Ng╞░ß╗¥i hß╗Åi:** SV08 ΓÇô QA/Security (Huß╗│nh Ph├║c ─Éß║ít)  
**Ng├áy:** 27/10/2026  
**Gß╗¡i tß╗¢i:** Lead (SV01), Backend (SV02, SV03), DevOps (SV07)

---

Tr╞░ß╗¢c khi viß║┐t ─æß║ºy ─æß╗º test case, QA cß║ºn x├íc nhß║¡n c├íc ─æiß╗âm sau ─æß╗â tr├ính viß║┐t test sai spec.
Mß╗ùi ─æiß╗âm c├│ giß║ú ─æß╗ïnh tß║ím thß╗¥i; nß║┐u giß║ú ─æß╗ïnh ─æ├║ng th├¼ kh├┤ng cß║ºn phß║ún hß╗ôi th├¬m.

---

## 1. API Contract (cß║ºn SV01 x├íc nhß║¡n)

| # | C├óu hß╗Åi | Giß║ú ─æß╗ïnh tß║ím thß╗¥i |
|---|---|---|
| Q-01 | Endpoint tß║ío ─æ╞ín POS l├á `POST /api/v1/orders/pos` hay `POST /api/v1/orders`? | `POST /api/v1/orders/pos` |
| Q-02 | Response body cß╗ºa login c├│ field `accessToken` hay `access_token`? | `accessToken` (camelCase theo SRS ┬º8.2) |
| Q-03 | Header idempotency ch├¡nh x├íc l├á `Idempotency-Key` hay `X-Idempotency-Key`? | `Idempotency-Key` |
| Q-04 | Webhook HMAC header l├á `X-Signature` vß╗¢i format `sha256=<hex>`? | ─É├║ng theo SRS ┬º8.3 |
| Q-05 | `openapi.yaml` bao giß╗¥ sß║╡n s├áng ─æß╗â QA viß║┐t test khß╗¢p spec? | Tuß║ºn 2 (SV01 merge) |
| Q-06 | Khi bß╗ï 403, body c├│ field `code` = `FORBIDDEN` kh├┤ng? | C├│, theo ─æß╗ïnh dß║íng lß╗ùi SRS ┬º8.2 |

---

## 2. Tß╗ôn kho ─æß╗ông thß╗¥i (cß║ºn SV03 x├íc nhß║¡n)

| # | C├óu hß╗Åi | Giß║ú ─æß╗ïnh tß║ím thß╗¥i |
|---|---|---|
| Q-07 | C╞í chß║┐ chß╗æng tß╗ôn ├óm d├╣ng `SELECT ... FOR UPDATE` hay `UPDATE ... WHERE available >= qty`? | `UPDATE ... WHERE available >= qty` (update c├│ ─æiß╗üu kiß╗çn, SRS ┬ºUC-09) |
| Q-08 | Khi tß╗ôn ├óm, lß╗ùi trß║ú vß╗ü l├á `409` vß╗¢i code `INSUFFICIENT_STOCK` hay code kh├íc? | `409` + `INSUFFICIENT_STOCK` |
| Q-09 | `CHECK constraint` trong DB c├│ t├¬n g├¼? ─Éß╗â QA verify trong migration SQL | `ck_inventory_non_negative` (giß║ú ─æß╗ïnh) |
| Q-10 | ─É╞ín nhiß╗üu d├▓ng cß║¡p nhß║¡t tß╗ôn theo thß╗⌐ tß╗▒ `product_id t─âng dß║ºn` ─æß╗â tr├ính deadlock ΓÇö ─æ├ú confirm sß║╜ implement vß║¡y? | ─É├║ng theo SRS ┬º5.3 |

---

## 3. X├íc thß╗▒c v├á bß║úo mß║¡t (cß║ºn SV02 x├íc nhß║¡n)

| # | C├óu hß╗Åi | Giß║ú ─æß╗ïnh tß║ím thß╗¥i |
|---|---|---|
| Q-11 | Brute force lockout sau 5 lß║ºn sai ΓÇö 15 ph├║t hay cß║Ñu h├¼nh ─æ╞░ß╗úc? | 15 ph├║t, cß║Ñu h├¼nh ─æ╞░ß╗úc qua biß║┐n m├┤i tr╞░ß╗¥ng |
| Q-12 | Khi t├ái khoß║ún bß╗ï kh├│a, status code l├á `401` hay `429`? | `401` vß╗¢i message r├╡ "t├ái khoß║ún bß╗ï kh├│a" |
| Q-13 | Nhß║¡t k├╜ ─æ─âng nhß║¡p thß║Ñt bß║íi ghi v├áo `audit_log` vß╗¢i `action = LOGIN_FAILED`? | ─É├║ng |
| Q-14 | `refresh_token` l╞░u ß╗ƒ ─æ├óu ΓÇö DB hay Redis? ─Éß╗â QA verify kh├┤ng bß╗ï replay sau khi logout | Redis (giß║ú ─æß╗ïnh ─æß╗â ─æ╞ín giß║ún) |
| Q-15 | Sau khi `POST /auth/logout`, refresh token c┼⌐ c├│ bß╗ï v├┤ hiß╗çu h├│a ngay kh├┤ng? | C├│ |

---

## 4. M├┤i tr╞░ß╗¥ng test (cß║ºn SV07 x├íc nhß║¡n)

| # | C├óu hß╗Åi | Giß║ú ─æß╗ïnh tß║ím thß╗¥i |
|---|---|---|
| Q-16 | `docker-compose.yml` sß║╡n s├áng ng├áy n├áo? QA cß║ºn ─æß╗â chß║íy test local | 03/11/2026 (deadline SV07) |
| Q-17 | Seed script (SV06) chß║íy bß║▒ng lß╗çnh g├¼? | `./scripts/seed.sh` hoß║╖c Maven/Gradle task |
| Q-18 | Staging URL sau khi SV07 setup CI/CD l├á g├¼? | Th├┤ng b├ío sau |
| Q-19 | Health check endpoint l├á `GET /actuator/health` (Spring Boot mß║╖c ─æß╗ïnh)? | ─É├║ng |
| Q-20 | Database test d├╣ng chung DB vß╗¢i dev hay c├│ DB ri├¬ng? | DB ri├¬ng tr├¬n Docker Compose |

---

## 5. Quy tß║»c nghiß╗çp vß╗Ñ c├▓n m╞í hß╗ô (cß║ºn SV01 x├íc nhß║¡n)

| # | C├óu hß╗Åi | ß║ónh h╞░ß╗ƒng test case |
|---|---|---|
| Q-21 | BR-07: "h├▓a nhau ΓåÆ chß╗ìn KM hß║┐t hß║ín sß╗¢m h╞ín" ΓÇö nß║┐u `end_at` bß║▒ng nhau th├¼ chß╗ìn theo `id` nhß╗Å h╞ín? | TC-POS-02 cß║ºn v├¡ dß╗Ñ sß╗æ ch├¡nh x├íc |
| Q-22 | Giß╗¢i hß║ín l╞░ß╗út d├╣ng KM (`usage_limit`) = NULL c├│ ngh─⌐a l├á kh├┤ng giß╗¢i hß║ín? | ß║ónh h╞░ß╗ƒng TC vß╗ü KM |
| Q-23 | ─É╞ín POS c├│ thß╗â c├│ 0 d├▓ng h├áng kh├┤ng? Hay bß║»t buß╗Öc ΓëÑ 1 d├▓ng? | TC validation ─æ╞ín POS |
| Q-24 | Khi `reserved_until` hß║┐t hß║ín m├á job ch╞░a chß║íy, tß╗ôn vß║½n bß╗ï giß╗» ΓÇö c├│ endpoint manual expire ─æß╗â test kh├┤ng? | TC-EXP-01 cß║ºn mock thß╗¥i gian hoß║╖c endpoint dev |

---

## ╞»u ti├¬n trß║ú lß╗¥i

**Cß║ºn ngay (tuß║ºn 1):** Q-01, Q-02, Q-03, Q-04, Q-16  
**Cß║ºn tr╞░ß╗¢c tuß║ºn 2:** Q-05, Q-07, Q-08, Q-11, Q-12  
**C├│ thß╗â trß║ú lß╗¥i tuß║ºn 3:** Q-21 ─æß║┐n Q-24

---

*Sß║╜ tß║ío Issues t╞░╞íng ß╗⌐ng nß║┐u sau daily 28/10 ch╞░a c├│ phß║ún hß╗ôi.*
