# Security Model & Threat Mitigation

## 1. Core Principles

GMDParser operates under a strict **Fail-Closed** security philosophy. If any element of a transaction is uncertain, missing, or contradictory, the system halts execution, refuses to write to the financial ledger, and prompts the user for manual review.

---

## 2. The Fail-Closed Invariants

| Condition | System Action | Behavioral Invariant |
|---|---|---|
| **Uncertain Category** | Halt & Prompt | Never guess or auto-assign an arbitrary category. Require user confirmation. |
| **Uncertain Account** | Halt & Prompt | Prompt user to pick the valid funding account from taxonomy. |
| **Uncertain Transaction Type** | Halt & Prompt | Surface ambiguity to user; never default to `Expenses`. |
| **Missing / Unparseable Amount** | Refuse Write | Immediate rejection. No write permitted without a verified positive amount. |
| **Missing Transaction Code** | Refuse Write | Immediate rejection. Transaction code is mandatory for idempotency. |
| **Duplicate Transaction Code** | Refuse Write | Return HTTP 409 Conflict. Never duplicate records. |
| **Transfer Missing Destination** | Refuse Write | Never downgrade an account transfer to an ordinary expense. |
| **Malformed Payload** | Refuse Write | Server-side validation failure (HTTP 400). |

---

## 3. Mandatory User Confirmation Model

Financial writes are strictly gated behind deliberate human action:

```
[M-PESA SMS Received]
        │
        ▼
[Local Deterministic Parser]
        │
        ▼
[Interactive Review Card Displayed to User]
        │
        ├── [User Modifies Classification if Needed]
        │
        ▼
[User Taps "Confirm & Write"]
        │
        ▼
[Encrypted Network Dispatch]
        │
        ▼
[Backend Server-Side Validation]
        │
        ▼
[Atomic Spreadsheet Append]
```

### Invariant:
* `isAutoSync()` is permanently locked to `false`.
* No background job or receiver may trigger a network write without explicit user intent.

---

## 4. Credential & Secret Isolation

```
[Android App] ──(Public Token)──> [Vercel Gateway] ──(Bearer Secret)──> [Apps Script Web App]
```

1. **Zero Google Credentials on Mobile**:
   - The Android client binary contains no Google Cloud Service Account keys, no Google Sheet IDs, and no Apps Script direct deployment URLs.
   - All client traffic routes through the public Vercel API gateway (`https://gmdarser.vercel.app`).
2. **Environment Variable Storage on Vercel**:
   - The private Apps Script execution URL and backend shared authorization token reside exclusively in Vercel secure environment variables:
     - `APPS_SCRIPT_URL`: Private deployment endpoint.
     - `GMD_API_SECRET`: Shared pre-shared key verified by Apps Script.
3. **Server-Side Verification**:
   - Google Apps Script does not execute anonymous writes. It verifies the incoming authorization header before touching the spreadsheet.

---

## 5. Ledger Integrity & Write Protection

1. **Formula Preservation**:
   - The Apps Script engine is constrained to specific column coordinates (`C:D`, `G:H`, `J:L`).
   - Formula columns (`A:B`, `E:F`, `I`, `P:BR`) are physically excluded from write ranges.
2. **Append-Only Protocol**:
   - Existing rows are strictly immutable to the API.
   - Deletion, row updates, or spreadsheet clearing operations are completely omitted from the API surface.
