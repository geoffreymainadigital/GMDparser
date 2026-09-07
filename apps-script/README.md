# Google Apps Script Backend (`apps-script/`)

This directory will contain the Google Apps Script project managed via `@google/clasp`.

## Planned Components:
- **`Code.gs`**: Core entry points (`doGet`, `doPost`).
- **Validation Engine**: Server-side payload, date, positive amount, and taxonomy validators.
- **Idempotency Guard**: Column `L` duplicate detection preventing repeated writes.
- **Safe Writer**: Bounded writes to `C:D`, `G:H`, and `J:L` without mutating formula columns (`A:B`, `E:F`, `I`, `P:BR`).
- **Invariants**:
  - Controlled write boundary.
  - Fail closed on missing/invalid taxonomy.
  - Returns structured JSON responses.
