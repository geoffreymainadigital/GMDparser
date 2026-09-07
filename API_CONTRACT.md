# Public & Internal API Contract

## 1. Gateway Overview

* **Base URL**: `https://gmdparser.vercel.app`
* **Protocol**: HTTPS (TLS 1.3 / 1.2)
* **Content-Type**: `application/json`
* **Authentication**: Token-based via `X-GMD-Auth-Key` request header.

The Android client targets `https://gmdparser.vercel.app` as its production base URL. Path endpoints are decoupled from the base URL.

---

## 2. Endpoints

### 2.1 Health Check: `GET /api/health`

Validates gateway status and upstream connectivity to Google Apps Script.

#### Response: `200 OK`
```json
{
  "status": "healthy",
  "service": "gmdparser-api",
  "version": "1.0.0",
  "upstream": {
    "appsScript": "connected",
    "sheetConnected": true,
    "responseTimeMs": 185
  },
  "timestamp": "2026-09-07T19:40:00Z"
}
```

---

### 2.2 Record Transaction: `POST /api/transaction`

Submits a user-confirmed M-PESA transaction for validation and insertion into Google Sheets.

#### Request Headers:
```http
POST /api/transaction HTTP/1.1
Host: gmdparser.vercel.app
Content-Type: application/json
X-GMD-Auth-Key: <client_auth_secret>
```

#### Request Payload Schema:
```json
{
  "action": "createTransaction",
  "transaction": {
    "date": "2026-09-07",
    "type": "Expenses",
    "category": "Food & Drinks",
    "description": "Naivas Supermarket",
    "amount": 2540.00,
    "account": "M-PESA",
    "transactionCode": "TD47XYZ123",
    "destinationAccount": null
  }
}
```

#### Field Validation Invariants:
* `date`: String, ISO format `YYYY-MM-DD` or `YYYY-MM-DDTHH:mm:ssZ`. Required.
* `type`: Enum (`Income`, `Expenses`, `Bills`, `Debt`, `Savings`, `Transfer`). Required.
* `category`: Non-empty string. Must match valid category for the given `type` in the spreadsheet taxonomy. Required.
* `description`: Non-empty string. Max 255 chars. Required.
* `amount`: Positive decimal number (`> 0.00`). Required.
* `account`: Non-empty string. Sourced from authoritative account list (e.g. `M-PESA`). Required.
* `transactionCode`: Alphanumeric string, 10 characters (standard M-PESA code). Required. Primary idempotency key.
* `destinationAccount`: Non-empty string if `type == "Transfer"`; `null` otherwise.

---

### 2.3 Response Codes & Schemas

#### Success: `201 Created`
```json
{
  "success": true,
  "status": "CREATED",
  "message": "Transaction recorded successfully",
  "data": {
    "row": 214,
    "transactionCode": "TD47XYZ123",
    "amount": 2540.00,
    "type": "Expenses",
    "category": "Food & Drinks",
    "account": "M-PESA",
    "timestamp": "2026-09-07T19:40:15Z"
  }
}
```

#### Duplicate Error (Idempotent Rejection): `409 Conflict`
```json
{
  "success": false,
  "status": "DUPLICATE_TRANSACTION_CODE",
  "error": "Transaction code TD47XYZ123 already recorded in row 188",
  "existingRecord": {
    "row": 188,
    "date": "2026-09-05",
    "amount": 2540.00,
    "transactionCode": "TD47XYZ123"
  }
}
```

#### Validation Failure: `400 Bad Request`
```json
{
  "success": false,
  "status": "VALIDATION_ERROR",
  "error": "Category 'Fast Food' is not a valid category for type 'Expenses'",
  "validationErrors": [
    {
      "field": "category",
      "rejectedValue": "Fast Food",
      "reason": "Must be one of: Groceries, Dining Out, Food & Drinks, Transport, ..."
    }
  ]
}
```

#### Transfer Missing Destination: `422 Unprocessable Entity`
```json
{
  "success": false,
  "status": "MISSING_DESTINATION_ACCOUNT",
  "error": "Transaction of type 'Transfer' requires a non-empty destinationAccount"
}
```

---

### 2.4 Taxonomy Sync: `GET /api/taxonomy`

Allows Android to query the current valid types, categories, and accounts from Google Sheets to populate selection UI.

#### Response: `200 OK`
```json
{
  "types": ["Income", "Expenses", "Bills", "Debt", "Savings", "Transfer"],
  "categoriesByType": {
    "Income": ["Salary", "Business", "Dividends", "Interest", "Refunds"],
    "Expenses": ["Food & Drinks", "Shopping", "Transport", "Entertainment", "Personal Care"],
    "Bills": ["Rent", "Electricity / KPLC", "Water", "Internet / WiFi", "Subscriptions"],
    "Debt": ["Credit Card", "Bank Loan", "Hustler Fund", "Personal Loan"],
    "Savings": ["Emergency Fund", "MMF Investment", "SACCO Shares", "Fixed Deposit"],
    "Transfer": ["Internal Account Transfer"]
  },
  "accounts": ["M-PESA", "NCBA Loop", "Equity Bank", "KCB", "Stima Sacco", "CIC MMF", "Cash"],
  "syncedAt": "2026-09-07T19:40:00Z"
}
```
