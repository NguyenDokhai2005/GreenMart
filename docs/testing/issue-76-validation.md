# Issue #76 — Client form validation

Implementation branch: `codex/76-client-validation`. PR #375 now targets `develop` after #73 and #74 merged.

- POS quantity: retain invalid input, show an associated inline error, cancel pending quote work and prevent new quotes/checkout until valid. Contract: integer 1–9999. Removing an invalid line clears its validation state.
- Customer lookup and quick creation: validate phone while editing with the existing contract validator; submit guards prevent invalid requests. Contract: `^0[0-9]{9,10}$`.
- Design approval: #174, Lead comment https://github.com/NguyenDokhai2005/GreenMart/issues/174#issuecomment-6894619331.

Validation result: typecheck, lint, format check, 288/288 tests (30 files) on the updated `develop` base, and production build passed. `git diff --check` passed.

Validation commands (frontend): `npm run typecheck`, `npm run lint`, `npm run format:check`, `npm test -- --run`, `npm run build`. POS suite includes invalid quantities 0, -1, 1.5, 10000 and empty input; asserts no quote request, disabled checkout, and recovery after correction. Invalid phone cases assert inline feedback and no lookup/create call.

Limitations: automated tests use mocked APIs. Local backend health was UP. In Chrome on the local POS with the seeded CASHIER account, a real product lookup and quote succeeded; entering quantity 0 showed an inline error and disabled checkout. Entering phone 123 showed its inline error; clicking Tìm khách left the lookup idle. The observed desktop layout kept the error beside the quantity field. Browser comparison against the exact approved Figma frame and a dedicated backend network trace remain outstanding. Issues #73 and #74 have merged. Issue closure requires PR #375 to pass CI, receive approval, and merge into `develop`.
