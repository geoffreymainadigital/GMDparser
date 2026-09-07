# Deployment Runbook & Environment Setup

## 1. Google Sheets Financial Model Preparation

1. **Spreadsheet Initialization**:
   - Verify the existence of the authoritative `Transactions` sheet and setup sheets (e.g., `Set up data 2`).
   - Confirm write boundary layout:
     - Date: `C`
     - Type: `D`
     - Category: `G`
     - Description: `H`
     - Ksh: `I`
     - Amount: `J`
     - Account: `K`
     - Transaction Code: `L`
2. **Column Protections**:
   - In Google Sheets: Select columns `A:B`, `E:F`, `I`, and `P:BR` -> Right click -> **Protect range** -> Set permissions to Owner Only.

---

## 2. Google Apps Script Backend Deployment (`apps-script/`)

1. **Clasp Setup**:
   - Using Node and `@google/clasp`:
     ```bash
     cd apps-script
     npx @google/clasp login
     npx @google/clasp create --title "GMDParser-Backend" --type sheets --parentId <SPREADSHEET_ID>
     ```
2. **Script Deployment**:
   - Push code:
     ```bash
     npx @google/clasp push
     ```
   - Deploy as Web App:
     ```bash
     npx @google/clasp deploy --description "GMDParser Production v1"
     ```
   - Execution Settings:
     - **Execute as**: *Me (Spreadsheet Owner)*
     - **Who has access**: *Anyone* (Calls are authenticated via `X-GMD-Auth-Key` inside the script).
3. **Environment Properties**:
   - Set Script Properties:
     - `GMD_AUTH_SECRET`: Strong pre-shared secret string.

---

## 3. Vercel API Layer Deployment (`web/`)

1. **Repository Connection**:
   - Link the GitHub repository to Vercel.
   - Configure Root Directory to `web/` (or repository root with `web/` serverless functions).
   - Set production branch to **`main`**. Do not use preview deployments as the production backend.
2. **Production Domain**:
   - Ensure the assigned production domain matches: `https://gmdarser.vercel.app`.
3. **Environment Variables**:
   Configure in the Vercel Dashboard for Production:
   - `APPS_SCRIPT_URL`: The full Google Apps Script web app URL (`https://script.google.com/macros/s/.../exec`).
   - `GMD_API_SECRET`: The shared secret matching `GMD_AUTH_SECRET` in Apps Script.
   - `CLIENT_API_KEY`: The public/client authentication token verified by Vercel for requests from Android.
4. **Deployment Verification**:
   ```bash
   curl -I https://gmdarser.vercel.app/api/health
   ```

---

## 4. Android Client Configuration (`android/`)

1. **Base URL Configuration**:
   - In `gradle.properties` or `BuildConfig`:
     ```kotlin
     buildConfigField("String", "BASE_URL", "\"https://gmdarser.vercel.app\"")
     buildConfigField("Boolean", "AUTO_SYNC_ENABLED", "false")
     ```
   - Endpoint paths remain decoupled:
     - Health: `/api/health`
     - Transaction Ingestion: `/api/transaction`
     - Taxonomy: `/api/taxonomy`
2. **Required Android Permissions**:
   - `android.permission.RECEIVE_SMS`
   - `android.permission.READ_SMS`
   - `android.permission.INTERNET`
   - `android.permission.ACCESS_NETWORK_STATE`
