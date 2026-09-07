# Data Model & Spreadsheet Data Contract

## 1. Google Sheets Ledger Contract

The primary ledger sheet is named **`Transactions`**.

### 1.1 Column Mapping Specification

| Column Index | Column Letter | Field Name | Type | Write Permission | Description / Invariants |
|---|---|---|---|---|---|
| 1-2 | `A:B` | *Internal / Formulas* | Formula / ID | **READ-ONLY / FORBIDDEN** | Auto-calculated metadata, row IDs, or balance indicators. Never overwrite. |
| 3 | `C` | **Date** | Date (`YYYY-MM-DD` or `DD/MM/YYYY`) | **WRITABLE** | Transaction posting date. |
| 4 | `D` | **Type** | String (Enum) | **WRITABLE** | Financial category type (`Income`, `Expenses`, `Bills`, `Debt`, `Savings`, `Transfer`). |
| 5-6 | `E:F` | *Internal Formulas* | Formula | **READ-ONLY / FORBIDDEN** | Month/Year or reporting helpers. Never overwrite. |
| 7 | `G` | **Category** | String | **WRITABLE** | Type-dependent category validated against taxonomy. |
| 8 | `H` | **Description** | String | **WRITABLE** | Clean payee/payer description or merchant name. |
| 9 | `I` | **Currency / Ksh** | Fixed / Formula | **READ-ONLY / FORBIDDEN** | Currency label column (`Ksh`). Must be preserved untouched. |
| 10 | `J` | **Amount** | Number (Decimal) | **WRITABLE** | Numerical transaction value in KES (positive float, 2 decimals). |
| 11 | `K` | **Account** | String | **WRITABLE** | Source funding account (e.g., `M-PESA`, `NCBA`, `Equity`, `Cash`). |
| 12 | `L` | **Transaction Code** | String (10 Alphanumeric) | **WRITABLE** | Unique M-PESA transaction identifier (e.g., `TD47XYZ123`). Primary key. |
| 13-15 | `M:O` | *Auxiliary Notes* | Optional String | *Reserved* | Additional audit flags or notes if configured. |
| 16-70 | `P:BR` | *Reporting Formulas* | Formulas | **READ-ONLY / FORBIDDEN** | Analytical reporting matrices, category allocations, budget lookups. |

---

## 2. Strict Write Boundaries

Apps Script must execute row additions by targeting strictly permitted column disjoint ranges:

```
[Row N]
├── Range C{N}:D{N} -> [Date, Type]
├── Range G{N}:H{N} -> [Category, Description]
└── Range J{N}:L{N} -> [Amount, Account, TransactionCode]
```

### Safety Rules:
1. **Never use `sheet.appendRow([all, columns, here])`**: A monolithic `appendRow` blindly writes starting at Column A, destroying formulas in `A:B`, `E:F`, and `I`.
2. **Deterministic Next-Row Calculation**:
   - Determine `nextRow` by scanning column `L` or column `C` downward to locate the first empty cell following historical records.
3. **Batch Bounded Write**:
   - Set values for `C{nextRow}:D{nextRow}`, `G{nextRow}:H{nextRow}`, and `J{nextRow}:L{nextRow}` in an atomic execution.

---

## 3. Financial Taxonomy & Categories

Categories are strictly hierarchical and depend on **Transaction Type**.

### 3.1 Known Core Transaction Types
* `Income`: Earnings, salary, business revenues, dividends.
* `Expenses`: Discretionary and living expenditures (food, transport, entertainment, shopping).
* `Bills`: Recurring fixed obligations (utilities, rent, internet, subscriptions).
* `Debt`: Debt repayments, credit cards, personal loan servicing.
* `Savings`: Emergency fund allocations, MMF deposits, SACCO shares.
* `Transfer`: Rebalancing funds between own accounts (e.g. M-PESA to Bank, M-PESA to MMF).

### 3.2 Authoritative Category Lookup (Phase 1 Baseline)
In the spreadsheet, categories are dynamically defined in setup sheets (e.g., `Set up data 2`).
Historical reference ranges (to be verified during Phase 1 inspection):
* **Income**: `Set up data 2!E6:E23`
* **Bills**: `Set up data 2!B27:B66`
* **Debt**: `Set up data 2!B70:B89`
* **Expenses**: `Set up data 2!B93:B122`
* **Savings**: `Set up data 2!B126:B145`

The application must **never** hardcode arbitrary categories. It dynamically validates against the active spreadsheet list.

---

## 4. Account Model & Balance Transfers

### 4.1 Accounts
Accounts represent asset or liability stores:
* `M-PESA`
* `Bank` (e.g., `KCB`, `Equity`, `NCBA Loop`, `Stanbic`)
* `Sacco` (e.g., `Stima Sacco`, `Harambee Sacco`)
* `MMF` (e.g., `CIC`, `Sanlam`, `Etica`, `Dry Associates`)
* `Cash`
* `Investment`

### 4.2 Account Transfers
* A balance transfer moves funds between two accounts without altering net worth.
* **Never convert a transfer into an expense.**
* Transfer payload attributes:
  - `sourceAccount`: Funding account (written to Column K: `Account`).
  - `destinationAccount`: Target account.
  - `amount`: KES amount transferred.
  - `transactionCode`: M-PESA transaction code.
  - `date`: Timestamp.
  - `description`: Transfer descriptor (e.g., "Transfer to NCBA Loop").

---

## 5. Idempotency & Deduplication Contract

1. **Unique Key**: M-PESA transaction code (e.g. `TD12AB34CD`).
2. **Check Protocol**: Before writing, Apps Script queries Column `L` across all populated rows.
3. **Resolution**: If a match is found:
   - Abort write operation immediately.
   - Return HTTP `409 Conflict` / JSON status `DUPLICATE_TRANSACTION_CODE`.
   - Include existing row index and recorded timestamp in response.
