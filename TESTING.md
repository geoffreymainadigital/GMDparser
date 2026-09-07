# Testing Strategy & Verification Protocol

## 1. Testing Philosophy & Strict Verification Rule

> **Absolute Rule**: *Do not claim a test passed unless it actually ran.*
> Every verification result must be backed by real execution output, HTTP response status, or test runner log.

---

## 2. Test Verification Matrix

### 2.1 Functional Transaction Scenarios
| Scenario ID | Test Name | Input Parameters | Expected Result | Layer |
|---|---|---|---|---|
| `TC-FN-01` | Valid Income | Type: `Income`, Cat: `Salary`, Amount: `150000`, Code: `TD01IN0001` | `201 Created`, Row appended to `C:D, G:H, J:L` | GAS / E2E |
| `TC-FN-02` | Valid Expense | Type: `Expenses`, Cat: `Food & Drinks`, Amount: `1200`, Code: `TD02EX0002` | `201 Created`, Correct category written | GAS / E2E |
| `TC-FN-03` | Valid Bill | Type: `Bills`, Cat: `Electricity / KPLC`, Amount: `3500`, Code: `TD03BL0003` | `201 Created`, Bill category preserved | GAS / E2E |
| `TC-FN-04` | Valid Debt Payment | Type: `Debt`, Cat: `Credit Card`, Amount: `10000`, Code: `TD04DB0004` | `201 Created`, Debt ledger updated | GAS / E2E |
| `TC-FN-05` | Valid Savings | Type: `Savings`, Cat: `MMF Investment`, Amount: `25000`, Code: `TD05SV0005` | `201 Created`, Savings row recorded | GAS / E2E |
| `TC-FN-06` | Account Transfer | Type: `Transfer`, Source: `M-PESA`, Dest: `NCBA Loop`, Amount: `5000`, Code: `TD06TR0006` | `201 Created`, Destination account preserved | GAS / E2E |

### 2.2 Rejection & Edge Cases
| Scenario ID | Test Name | Input Condition | Expected Rejection |
|---|---|---|---|
| `TC-ERR-01` | Duplicate Code | Code `TD01IN0001` submitted again | `409 Conflict`, `DUPLICATE_TRANSACTION_CODE` |
| `TC-ERR-02` | Missing Code | `transactionCode: ""` or `null` | `400 Bad Request`, `MISSING_CODE` |
| `TC-ERR-03` | Invalid Amount | `amount: -500` or `amount: 0` | `400 Bad Request`, `INVALID_AMOUNT` |
| `TC-ERR-04` | Invalid Category | Category not present in spreadsheet taxonomy for given type | `400 Bad Request`, `INVALID_CATEGORY` |
| `TC-ERR-05` | Invalid Account | Account not in spreadsheet account list | `400 Bad Request`, `INVALID_ACCOUNT` |
| `TC-ERR-06` | Invalid Type | Unknown financial type e.g., `Miscellaneous` | `400 Bad Request`, `INVALID_TYPE` |
| `TC-ERR-07` | Transfer Without Destination | `type: "Transfer"`, `destinationAccount: null` | `422 Unprocessable Entity`, no conversion to Expense |

### 2.3 Spreadsheet Integrity Checks
| Scenario ID | Test Name | Verification Method |
|---|---|---|
| `TC-SP-01` | Historical Data Immutability | Compare row hashes of rows `1` through `N-1` before and after insert; ensure 0 diff. |
| `TC-SP-02` | Formula Column Preservation | Check formula strings in Columns `A`, `B`, `E`, `F`, `I`, `P:BR` on inserted row; confirm no overwrites. |
| `TC-SP-03` | Data Validation Preservation | Ensure native Google Sheets dropdown rules on Column D (Type) and Column G (Category) remain active. |

### 2.4 SMS Parser Scenarios (Android Unit Tests)
| Scenario ID | SMS Pattern | Extracted Parameters |
|---|---|---|
| `TC-SMS-01` | Paybill (e.g. KPLC) | Code, Amount, Paybill Account No, Business Name, Balance |
| `TC-SMS-02` | Buy Goods (Till) | Code, Amount, Merchant Name, Till Number, Balance |
| `TC-SMS-03` | Send Money (P2P) | Code, Amount, Recipient Name, Phone Number, Balance |
| `TC-SMS-04` | Received Money | Code, Amount, Sender Name, Phone Number, Balance |
| `TC-SMS-05` | Bank to M-PESA / Transfer | Code, Amount, Bank Account descriptor, Balance |
| `TC-SMS-06` | Ambiguous SMS | Flagged as unconfident; user confirmation prompted |
| `TC-SMS-07` | Non-financial / Promotional | Filtered out; ignored |

### 2.5 Integration Tests
| Scenario ID | Pipe | Test Target |
|---|---|---|
| `TC-INT-01` | Vercel → Apps Script | Validate proxy relay, headers, and timeout handling |
| `TC-INT-02` | Android → Vercel | End-to-end latency, error payload propagation, TLS compatibility |
| `TC-INT-03` | User Confirmation E2E | SMS → Parsed Card → User Edit → User Confirm → Sheet Row Verified |
