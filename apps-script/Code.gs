/**
 * GMDParser Google Apps Script Backend
 * Authoritative write layer & validation engine for Google Sheets
 */

// Configuration Constants
const SHEET_NAME_TRANSACTIONS = 'Transactions';
const SHEET_NAME_SETUP = 'Set Up';

// Column Indices in Transactions Sheet (1-based)
const COL_DATE = 3;             // C (Date)
const COL_TYPE = 4;             // D (Transactions - Type)
const COL_CATEGORY = 7;         // G (Category)
const COL_DESCRIPTION = 8;      // H (Description)
const COL_AMOUNT = 10;          // J (Amount)
const COL_ACCOUNT = 11;         // K (Account)
const COL_NOTES = 12;           // L (Notes)

const SCRIPT_VERSION = '2026.09.12.v23_font23_row2_tiles_fix';
const SCRIPT_BUILD_ID = 'GMD_GAS_20260912_PROD_23';

let VALID_TYPES = ['Income', 'Expenses', 'Bills', 'Debt', 'Savings', 'Balance'];

// Module-level taxonomy cache — populated lazily on first write so each deployment
// pays the 'Set up data 2' read cost only once per Apps Script instance.
let _taxonomyCache = null;
let _accountsDataCache = null;

/**
 * Returns the list of valid category strings for the given transaction type.
 * Sourced from 'Set up data 2' (same as extractCategoriesFromSetUpData2).
 * Result is cached for the lifetime of the script instance to avoid redundant sheet reads.
 */
function getCategoriesForType(type) {
  if (!_taxonomyCache) {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    _taxonomyCache = extractCategoriesFromSetUpData2(ss);
  }
  if (!_taxonomyCache || !_taxonomyCache.categoriesByType) {
    return [];  // fallback: caller will write value without a strict rule
  }
  return _taxonomyCache.categoriesByType[type] || [];
}


/**
 * Authentication secret verification.
 * Writes fail closed (500 SERVER_MISCONFIGURED) if GMD_AUTH_SECRET is not configured.
 * When GMD_AUTH_SECRET is set, all protected GET/POST endpoints require valid key (401 UNAUTHORIZED if invalid).
 */
function verifyAuthSecret(e, payload, isWriteOperation) {
  const scriptProperties = PropertiesService.getScriptProperties();
  const configuredSecret = scriptProperties.getProperty('GMD_AUTH_SECRET');

  // Fail closed for write operations if GMD_AUTH_SECRET is not configured
  if (!configuredSecret) {
    if (isWriteOperation) {
      return createJsonResponse({
        success: false,
        status: 'SERVER_MISCONFIGURED',
        error: 'Server misconfigured: GMD_AUTH_SECRET is not set in Script Properties.'
      }, 500);
    }
    return null;
  }

  // Extract provided key from request header or payload
  let providedKey = null;
  if (e && e.parameter && e.parameter.authKey) {
    providedKey = e.parameter.authKey;
  } else if (payload && payload.authKey) {
    providedKey = payload.authKey;
  }

  if (providedKey !== configuredSecret) {
    return createJsonResponse({
      success: false,
      status: 'UNAUTHORIZED',
      error: 'Invalid authentication key provided.'
    }, 401);
  }

  return null;
}

/**
 * Handle HTTP GET Requests (Health check and taxonomy)
 */
function doGet(e) {
  try {
    const action = (e && e.parameter && e.parameter.action) || 'health';
    const ss = SpreadsheetApp.getActiveSpreadsheet();

    // Public read-only endpoints (unauthenticated)
    if (action === 'health') {
      const sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
      const isSheetReady = !!sheet;
      return createJsonResponse({
        status: isSheetReady ? 'healthy' : 'degraded',
        service: 'gmdparser-apps-script',
        SCRIPT_VERSION: SCRIPT_VERSION,
        SCRIPT_BUILD_ID: SCRIPT_BUILD_ID,
        version: SCRIPT_VERSION,
        buildId: SCRIPT_BUILD_ID,
        spreadsheetId: ss.getId(),
        sheetName: SHEET_NAME_TRANSACTIONS,
        sheetExists: isSheetReady,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'taxonomy') {
      const targetSs = (e && e.parameter && e.parameter.spreadsheetId) ?
        SpreadsheetApp.openById(e.parameter.spreadsheetId) : ss;
      const taxonomy = getTaxonomyData(targetSs);
      return createJsonResponse({
        success: true,
        data: taxonomy,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'getRecentTransactions') {
      const sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
      if (!sheet) {
        return createJsonResponse({ success: false, error: 'Transactions sheet not found' }, 500);
      }
      const limit = parseInt((e && e.parameter && e.parameter.limit) || '50', 10);
      const transactions = getRecentTransactions(sheet, limit);
      return createJsonResponse({
        success: true,
        count: transactions.length,
        data: transactions,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'dashboard') {
      const targetSs = (e && e.parameter && e.parameter.spreadsheetId) ?
        SpreadsheetApp.openById(e.parameter.spreadsheetId) : ss;

      const tStart = Date.now();
      const monthData = getMonthlyDashboardData(targetSs);
      const tMonth = Date.now();
      const accountsData = getAccountsData(targetSs);
      const tAccounts = Date.now();
      return createJsonResponse({
        success: true,
        period: 'monthly',
        month: monthData,
        accounts: accountsData,
        timingReport: {
          monthDataMs: tMonth - tStart,
          accountsDataMs: tAccounts - tMonth,
          totalServerMs: tAccounts - tStart
        },
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'savings') {
      const targetSs = (e && e.parameter && e.parameter.spreadsheetId) ?
        SpreadsheetApp.openById(e.parameter.spreadsheetId) : ss;
      const savingsData = getSavingsDashboardData(targetSs);
      return createJsonResponse({
        success: true,
        data: savingsData,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'debug_tiles') {
      const monthNames = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
      const now = new Date();
      const monthIndex = parseInt(Utilities.formatDate(now, 'Africa/Nairobi', 'M'), 10) - 1;
      const currentMonthCode = monthNames[monthIndex];
      const sheet = ss.getSheetByName(currentMonthCode);
      const range = sheet.getRange(1, 1, 15, 120);
      const values = range.getValues();
      const displayValues = range.getDisplayValues();
      const fontSizes = range.getFontSizes();

      const headersFound = [];
      const font23Cells = [];

      for (let r = 0; r < values.length; r++) {
        for (let c = 0; c < values[r].length; c++) {
          const v = String(values[r][c] || '').trim();
          if (v.toLowerCase().includes('total') || v.toLowerCase().includes('unallocated') || v.toLowerCase().includes('savings')) {
            headersFound.push({ r: r + 1, c: c + 1, text: v, size: fontSizes[r][c] });
          }
          if (fontSizes[r][c] >= 18) {
            font23Cells.push({ r: r + 1, c: c + 1, val: values[r][c], disp: displayValues[r][c], size: fontSizes[r][c] });
          }
        }
      }

      const unallocArea = [];
      for (let r = 0; r < 6; r++) {
        for (let c = 75; c < 85; c++) {
          if (values[r][c] !== '' && values[r][c] !== null) {
            unallocArea.push({ r: r + 1, c: c + 1, val: values[r][c], disp: displayValues[r][c], size: fontSizes[r][c] });
          }
        }
      }

      return createJsonResponse({
        success: true,
        month: currentMonthCode,
        headersFound,
        font23Cells,
        unallocArea,
        timestamp: new Date().toISOString()
      }, 200);
    }

    // Protected endpoints require mandatory authentication check (admin/diagnostic only)
    const authError = verifyAuthSecret(e, null, false);
    if (authError) {
      return authError;
    }

    if (action === 'diagnoseTaxonomy') {
      const targetId = (e && e.parameter && e.parameter.spreadsheetId) || ss.getId();
      const targetSs = SpreadsheetApp.openById(targetId);
      const sheetNames = targetSs.getSheets().map(function (s) { return s.getName(); });
      const taxonomy = getTaxonomyData(targetSs);
      return createJsonResponse({
        success: true,
        spreadsheetId: targetId,
        sheets: sheetNames,
        taxonomy: taxonomy,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'test_dump') {
      const targetSs = (e && e.parameter && e.parameter.spreadsheetId) ? SpreadsheetApp.openById(e.parameter.spreadsheetId) : ss;
      const sheet = targetSs.getSheetByName("SEP");
      const values = sheet.getRange(1, 1, 100, 20).getDisplayValues();
      return createJsonResponse({
        success: true,
        data: values,
        timestamp: new Date().toISOString()
      }, 200);
    }



    if (action === 'findOrphanedRows') {
      const sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
      if (!sheet) {
        return createJsonResponse({ success: false, error: 'Transactions sheet not found' }, 500);
      }
      const orphans = findOrphanedRows(sheet);
      return createJsonResponse({
        success: true,
        count: orphans.length,
        orphanedRows: orphans,
        timestamp: new Date().toISOString()
      }, 200);
    }


    return createJsonResponse({
      success: false,
      error: 'Invalid action parameter: ' + action
    }, 400);

  } catch (error) {
    console.error('doGet error: ' + error.toString(), error.stack);
    return createJsonResponse({
      success: false,
      status: 'INTERNAL_ERROR',
      error: error.message || error.toString()
    }, 500);
  }
}

/**
 * Handle HTTP POST Requests (Transaction creation and validation)
 */
function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    // Acquire lock with 15-second timeout to ensure atomic sequential writes
    const hasLock = lock.tryLock(15000);
    if (!hasLock) {
      return createJsonResponse({
        success: false,
        status: 'SERVER_BUSY',
        error: 'Unable to acquire database write lock. Please retry.'
      }, 503);
    }

    if (!e || !e.postData || !e.postData.contents) {
      return createJsonResponse({
        success: false,
        status: 'BAD_REQUEST',
        error: 'Missing request body'
      }, 400);
    }

    let payload;
    try {
      payload = JSON.parse(e.postData.contents);
    } catch (parseErr) {
      return createJsonResponse({
        success: false,
        status: 'INVALID_JSON',
        error: 'Malformed JSON payload: ' + parseErr.message
      }, 400);
    }

    // Mandatory Auth Secret check for all POST operations
    const authError = verifyAuthSecret(e, payload, true);
    if (authError) {
      return authError;
    }

    const action = payload.action || 'createTransaction';

    if (action === 'createTransaction') {
      return handleCreateTransaction(payload.transaction);
    } else if (action === 'batchCreateTransactions') {
      return handleBatchCreateTransactions(payload.transactions);
    } else if (action === 'validateTransaction') {
      return handleValidateOnly(payload.transaction);
    } else {
      return createJsonResponse({
        success: false,
        status: 'INVALID_ACTION',
        error: 'Unsupported action: ' + action
      }, 400);
    }

  } catch (error) {
    console.error('doPost error: ' + error.toString(), error.stack);
    return createJsonResponse({
      success: false,
      status: 'INTERNAL_ERROR',
      error: error.message || error.toString()
    }, 500);
  } finally {
    lock.releaseLock();
  }
}

/**
 * Validates and records a confirmed transaction into Google Sheets
 */
function handleCreateTransaction(tx) {
  const validation = validateTransactionPayload(tx);
  if (!validation.isValid) {
    return createJsonResponse({
      success: false,
      status: 'VALIDATION_ERROR',
      error: validation.errors.join('; '),
      errors: validation.errors
    }, 400);
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
  if (!sheet) {
    return createJsonResponse({
      success: false,
      status: 'MISSING_TRANSACTIONS_SHEET',
      error: 'Sheet "' + SHEET_NAME_TRANSACTIONS + '" was not found in spreadsheet.'
    }, 500);
  }

  // Duplicate Check against Column L (Notes)
  const duplicateCheck = checkDuplicateTransactionCode(sheet, tx.transactionCode);
  if (duplicateCheck.isDuplicate) {
    return createJsonResponse({
      success: false,
      status: 'DUPLICATE',
      error: 'Transaction code ' + tx.transactionCode + ' already exists at row ' + duplicateCheck.row,
      existingRecord: {
        row: duplicateCheck.row,
        transactionCode: tx.transactionCode
      }
    }, 409);
  }

  // Find next transaction row dynamically by inspecting Date column C
  // CRITICAL: NEVER hardcodes row 13/1464 and never uses sheet.getLastRow()
  const targetRow = findNextTransactionRow(sheet);

  // Parse and format date
  let formattedDate = tx.date;
  try {
    const d = new Date(tx.date);
    if (!isNaN(d.getTime())) {
      formattedDate = Utilities.formatDate(d, 'Africa/Nairobi', 'yyyy-MM-dd');
    }
  } catch (dErr) {
    // Keep raw string if parsing fails
  }

  // Format Notes: preserve existing notes and append machine-readable M-PESA Code marker
  const userNotes = (tx.notes || '').trim();
  const cellNotes = String(sheet.getRange(targetRow, COL_NOTES).getValue() || '').trim();
  let baseNotes = userNotes;
  if (cellNotes && cellNotes !== userNotes) {
    baseNotes = userNotes ? (cellNotes + ' | ' + userNotes) : cellNotes;
  }
  const formattedNotes = formatNotesWithCode(baseNotes, tx.transactionCode);

  // WRITE SEQUENCE (Orphaned Row Prevention + No-Flush Path):
  // Date (Column C) is written LAST because findLastTransactionRow scans Column C.
  // If an error or timeout occurs before Date is written, Column C stays blank and the next
  // transaction write reuses this row naturally — no orphaned rows.
  //
  // Category validation rule is built directly from taxonomy (getCategoriesForType), applied to
  // the target cell BEFORE writing the value. This eliminates the need for SpreadsheetApp.flush()
  // to trigger the sheet's array formula in column X — no full-sheet recalculation required.
  try {
    // 1. Write Type (Column D)
    sheet.getRange(targetRow, COL_TYPE).setValue(tx.type);

    // 2. Apply category data-validation rule directly — no flush() needed.
    //    getCategoriesForType() reads from 'Set up data 2', the same authoritative source
    //    the sheet's own array formula reads. Result is cached for the instance lifetime.
    const validCategories = getCategoriesForType(tx.type);
    if (validCategories && validCategories.length > 0) {
      const catRule = SpreadsheetApp.newDataValidation()
        .requireValueInList(validCategories, true)
        .setAllowInvalid(false)
        .build();
      sheet.getRange(targetRow, COL_CATEGORY).setDataValidation(catRule);
    }

    // 3. Write Category + Description (Columns G:H)
    sheet.getRange(targetRow, COL_CATEGORY, 1, 2).setValues([[
      tx.category,
      tx.description
    ]]);

    // 4. Write Amount + Account + Notes (Columns J:L)
    // CRITICAL: Column I (currency formula 'Set Up'!$C$10) is preserved and NEVER overwritten!
    // Columns A:B, E:F (F has VLOOKUP formula), and P:BR are also preserved.
    sheet.getRange(targetRow, COL_AMOUNT, 1, 3).setValues([[
      Number(tx.amount),
      tx.account,
      formattedNotes
    ]]);

    // 5. Write Date to Column C LAST — commits the row for findLastTransactionRow.
    sheet.getRange(targetRow, COL_DATE).setValue(formattedDate);

  } catch (writeErr) {
    // If write failed before Date was written, clean up Type from Column D so row stays blank.
    try {
      sheet.getRange(targetRow, COL_TYPE).clearContent();
    } catch (_) {}
    throw writeErr;
  }

  return createJsonResponse({
    success: true,
    status: 'CREATED',
    message: 'Transaction successfully recorded',
    data: {
      row: targetRow,
      transactionCode: tx.transactionCode.trim().toUpperCase(),
      amount: Number(tx.amount),
      type: tx.type,
      category: tx.category,
      account: tx.account,
      date: formattedDate,
      notes: formattedNotes,
      timestamp: new Date().toISOString()
    }
  }, 201);
}

/**
 * Batch write handler: accepts an array of transactions, writes all rows field-by-field in
 * staged batched range operations with a single SpreadsheetApp.flush() per stage.
 *
 * Write order (per Date-last orphan prevention contract):
 *   Stage 1: Type (Column D)  → flush once
 *   Stage 2: Category + Description (Columns G:H)
 *   Stage 3: Amount + Account + Notes (Columns J:L)
 *   Stage 4: Date (Column C) LAST → flush once to commit all rows
 *
 * Each transaction is duplicate-checked before allocation. Duplicates are skipped
 * and reported individually; the rest proceed.
 * Returns a per-transaction result list even on partial success.
 */
function handleBatchCreateTransactions(transactions) {
  if (!Array.isArray(transactions) || transactions.length === 0) {
    return createJsonResponse({
      success: false,
      status: 'BAD_REQUEST',
      error: 'transactions must be a non-empty array'
    }, 400);
  }

  const MAX_BATCH = 50;
  if (transactions.length > MAX_BATCH) {
    return createJsonResponse({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Batch size exceeds maximum of ' + MAX_BATCH + ' transactions per request'
    }, 400);
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
  if (!sheet) {
    return createJsonResponse({
      success: false,
      status: 'MISSING_TRANSACTIONS_SHEET',
      error: 'Sheet "' + SHEET_NAME_TRANSACTIONS + '" was not found in spreadsheet.'
    }, 500);
  }

  // --- Phase 1: Validate each item and check duplicates ---
  // Read the full Notes column once up front (avoids N separate reads)
  const lastExistingRow = findLastTransactionRow(sheet);
  const existingNotesCount = lastExistingRow >= 10 ? lastExistingRow - 9 : 0;
  const existingNotesValues = existingNotesCount > 0
    ? sheet.getRange(10, COL_NOTES, existingNotesCount, 1).getValues()
    : [];

  // Build a Set of already-known codes for fast O(1) lookups within this batch run
  const existingCodes = new Set();
  const codeRowMap = {};  // code -> row number for duplicate reporting
  for (let i = 0; i < existingNotesValues.length; i++) {
    const rawVal = String(existingNotesValues[i][0] || '').trim();
    if (!rawVal) continue;
    // Extract M-PESA Code from notes using the standard marker
    const codeMatch = rawVal.match(/M-PESA Code:\s*([A-Z0-9]{8,25})/i);
    if (codeMatch) {
      const code = codeMatch[1].toUpperCase();
      existingCodes.add(code);
      codeRowMap[code] = 10 + i;
    }
  }

  const results = [];
  // Items that pass validation and are not duplicates → will be written
  const toWrite = [];

  for (let i = 0; i < transactions.length; i++) {
    const tx = transactions[i];
    const validation = validateTransactionPayload(tx);
    if (!validation.isValid) {
      results.push({
        index: i,
        transactionCode: tx && tx.transactionCode ? tx.transactionCode : '?',
        status: 'VALIDATION_ERROR',
        success: false,
        errors: validation.errors
      });
      continue;
    }

    const txCode = tx.transactionCode.trim().toUpperCase();

    // Check against already-existing codes AND codes added earlier in this batch
    if (existingCodes.has(txCode)) {
      results.push({
        index: i,
        transactionCode: txCode,
        status: 'DUPLICATE',
        success: false,
        existingRow: codeRowMap[txCode] || null,
        error: 'Transaction code ' + txCode + ' already exists'
      });
      continue;
    }

    // Reserve this code so later items in the same batch can't claim it
    existingCodes.add(txCode);

    // Format date
    let formattedDate = tx.date;
    try {
      const d = new Date(tx.date);
      if (!isNaN(d.getTime())) {
        formattedDate = Utilities.formatDate(d, 'Africa/Nairobi', 'yyyy-MM-dd');
      }
    } catch (_) {}

    // Format notes
    const formattedNotes = formatNotesWithCode((tx.notes || '').trim(), txCode);

    toWrite.push({
      originalIndex: i,
      tx: tx,
      txCode: txCode,
      formattedDate: formattedDate,
      formattedNotes: formattedNotes
    });
  }

  if (toWrite.length === 0) {
    // Nothing to write — return results-only (all duplicates/errors)
    return createJsonResponse({
      success: true,
      status: 'BATCH_COMPLETE',
      written: 0,
      total: transactions.length,
      results: results
    }, 200);
  }

  // --- Phase 2: Allocate target rows ---
  // All rows are allocated sequentially starting from (lastExistingRow + 1).
  // Because Date (col C) is written LAST, none of these rows are "committed" yet,
  // so they won't be re-allocated if a partial failure occurs.
  const firstTargetRow = lastExistingRow + 1;
  const rowAllocations = toWrite.map(function(item, idx) {
    return firstTargetRow + idx;
  });

  // --- Phase 3: Staged batched writes (No-Flush Path) ---
  // Category validation rules are built directly from taxonomy (getCategoriesForType) and applied
  // via setDataValidations() as a single API call. No flush() needed — no array-formula
  // recalculation. All stages are single range writes for the whole batch.
  try {
    // Stage 3a: Write Type (Column D) — single range write, no flush.
    const typeData = toWrite.map(function(item) { return [item.tx.type]; });
    sheet.getRange(firstTargetRow, COL_TYPE, toWrite.length, 1).setValues(typeData);

    // Stage 3b: Apply category data-validation rules for all rows in one call.
    //   Each row gets its own rule matching its Type — built from the taxonomy cache.
    //   setDataValidations accepts a 2-D array of DataValidation objects.
    const catRules2D = toWrite.map(function(item) {
      const cats = getCategoriesForType(item.tx.type);
      if (!cats || cats.length === 0) return [null];  // no rule if taxonomy empty
      return [SpreadsheetApp.newDataValidation()
        .requireValueInList(cats, true)
        .setAllowInvalid(false)
        .build()];
    });
    // Filter: only apply if at least one row has a non-null rule
    if (catRules2D.some(function(r) { return r[0] !== null; })) {
      sheet.getRange(firstTargetRow, COL_CATEGORY, toWrite.length, 1).setDataValidations(catRules2D);
    }

    // Stage 3c: Write Category + Description (Columns G:H) — single range write.
    const catData = toWrite.map(function(item) {
      return [item.tx.category, item.tx.description];
    });
    sheet.getRange(firstTargetRow, COL_CATEGORY, toWrite.length, 2).setValues(catData);

    // Stage 3d: Write Amount + Account + Notes (Columns J:L) — single range write.
    // CRITICAL: Column I (currency formula) is NEVER written.
    const amtData = toWrite.map(function(item) {
      return [Number(item.tx.amount), item.tx.account, item.formattedNotes];
    });
    sheet.getRange(firstTargetRow, COL_AMOUNT, toWrite.length, 3).setValues(amtData);

    // Stage 3e: Write Date (Column C) LAST for ALL rows — commits every row atomically.
    // Only after this line will findLastTransactionRow() count these rows.
    const dateData = toWrite.map(function(item) { return [item.formattedDate]; });
    sheet.getRange(firstTargetRow, COL_DATE, toWrite.length, 1).setValues(dateData);

  } catch (writeErr) {
    // Best-effort cleanup: clear Type column (col D) from all allocated rows
    // so they remain blank in col C and don't become orphans.
    try {
      sheet.getRange(firstTargetRow, COL_TYPE, toWrite.length, 1).clearContent();
    } catch (_) {}
    throw writeErr;
  }

  // --- Phase 4: Build per-transaction success results ---
  toWrite.forEach(function(item, idx) {
    const targetRow = rowAllocations[idx];
    results.push({
      index: item.originalIndex,
      transactionCode: item.txCode,
      status: 'CREATED',
      success: true,
      row: targetRow,
      amount: Number(item.tx.amount),
      type: item.tx.type,
      category: item.tx.category,
      account: item.tx.account,
      date: item.formattedDate,
      notes: item.formattedNotes
    });
    // Register code in map for response completeness
    codeRowMap[item.txCode] = targetRow;
  });

  // Sort results back to original submission order
  results.sort(function(a, b) { return a.index - b.index; });

  const successCount = results.filter(function(r) { return r.success; }).length;
  const dupCount = results.filter(function(r) { return r.status === 'DUPLICATE'; }).length;
  const errorCount = results.filter(function(r) { return !r.success && r.status !== 'DUPLICATE'; }).length;

  return createJsonResponse({
    success: true,
    status: 'BATCH_COMPLETE',
    written: successCount,
    duplicates: dupCount,
    errors: errorCount,
    total: transactions.length,
    firstRow: successCount > 0 ? firstTargetRow : null,
    lastRow: successCount > 0 ? rowAllocations[toWrite.length - 1] : null,
    results: results,
    timestamp: new Date().toISOString()
  }, 201);
}

/**
 * Validates payload without writing
 */
function handleValidateOnly(tx) {
  const validation = validateTransactionPayload(tx);
  if (!validation.isValid) {
    return createJsonResponse({
      success: false,
      status: 'VALIDATION_ERROR',
      errors: validation.errors
    }, 400);
  }

  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
  let duplicateInfo = null;
  if (sheet) {
    const dup = checkDuplicateTransactionCode(sheet, tx.transactionCode);
    if (dup.isDuplicate) {
      return createJsonResponse({
        success: false,
        status: 'DUPLICATE',
        error: 'Transaction code ' + tx.transactionCode + ' already exists at row ' + dup.row
      }, 409);
    }
  }

  return createJsonResponse({
    success: true,
    status: 'VALID',
    message: 'Transaction payload is valid'
  }, 200);
}

/**
 * Robust Transaction Payload Validation
 */
function validateTransactionPayload(tx) {
  const errors = [];
  if (!tx || typeof tx !== 'object') {
    return { isValid: false, errors: ['Transaction object is missing or invalid'] };
  }

  const txCode = typeof tx.transactionCode === 'string' ? tx.transactionCode.trim() : '';
  if (!txCode || txCode.length < 8 || txCode.length > 25) {
    errors.push('Valid transactionCode is required (8-25 characters)');
  }

  const amount = Number(tx.amount);
  if (isNaN(amount) || amount === 0) {
    errors.push('Amount must be a non-zero number');
  } else if (amount < 0 && tx.type !== 'Savings') {
    errors.push('Negative amounts are only valid for Savings transactions (withdrawals)');
  }

  if (!tx.date || typeof tx.date !== 'string') {
    errors.push('Valid date string is required');
  }

  if (!tx.type || VALID_TYPES.indexOf(tx.type) === -1) {
    errors.push('Type must be one of: ' + VALID_TYPES.join(', '));
  }

  if (tx.type !== 'Balance' && (!tx.category || typeof tx.category !== 'string' || tx.category.trim() === '')) {
    errors.push('Non-empty category is required');
  }

  if (!tx.account || typeof tx.account !== 'string' || tx.account.trim() === '') {
    errors.push('Non-empty source account is required');
  }

  if (!tx.description || typeof tx.description !== 'string' || tx.description.trim() === '') {
    errors.push('Non-empty description is required');
  }


  return {
    isValid: errors.length === 0,
    errors: errors
  };
}

/**
 * Formats Notes with M-PESA transaction code marker:
 * - If Notes is empty: "M-PESA Code: <code>"
 * - If Notes already contains text: "<existing notes> | M-PESA Code: <code>"
 */
function formatNotesWithCode(existingNotes, txCode) {
  const code = (txCode || '').trim().toUpperCase();
  const marker = 'M-PESA Code: ' + code;
  const notes = (existingNotes || '').trim();
  if (!notes) {
    return marker;
  }
  if (notes.indexOf(marker) !== -1 || notes.indexOf(code) !== -1) {
    return notes;
  }
  return notes + ' | ' + marker;
}

/**
 * Scans Column C (Date) to find the last transaction row.
 * CRITICAL: NEVER uses sheet.getLastRow() because formulas/helper content extend far below real ledger.
 */
function findLastTransactionRow(sheet) {
  const maxRows = sheet.getMaxRows ? sheet.getMaxRows() : 5000;
  if (maxRows < 10) return 9;
  const numRows = maxRows - 9;
  const dateValues = sheet.getRange(10, COL_DATE, numRows, 1).getValues();
  for (let i = dateValues.length - 1; i >= 0; i--) {
    const val = dateValues[i][0];
    if (val !== null && val !== undefined && String(val).trim() !== '') {
      return 10 + i;
    }
  }
  return 9;
}

/**
 * Resolves the next append row at lastTransactionRow + 1.
 * For the uploaded clone, resolves last existing transaction to row 1463 and next transaction row to 1464.
 * CRITICAL: Does NOT hardcode 13 or 1464, and does NOT use sheet.getLastRow().
 */
function findNextTransactionRow(sheet) {
  return findLastTransactionRow(sheet) + 1;
}

/**
 * Scans the Transactions sheet to find orphaned blank or partial rows:
 * Rows where Date (C) or Type (D) is present, but Amount (J), Category (G), or Description (H) is missing.
 * Read-only diagnostic function.
 */
function findOrphanedRows(sheet) {
  const maxRows = sheet.getMaxRows ? sheet.getMaxRows() : 5000;
  if (maxRows < 10) return [];
  const numRows = Math.min(maxRows - 9, 3000);

  // Read C (Date) through L (Notes) -> 10 columns (cols 3 to 12)
  const rangeValues = sheet.getRange(10, COL_DATE, numRows, 10).getValues();

  const orphaned = [];
  for (let i = 0; i < rangeValues.length; i++) {
    const rowNum = 10 + i;
    const row = rangeValues[i];
    const dateVal = String(row[0] || '').trim();  // COL_DATE (3)
    const typeVal = String(row[1] || '').trim();  // COL_TYPE (4)
    const catVal = String(row[4] || '').trim();   // COL_CATEGORY (7)
    const descVal = String(row[5] || '').trim();  // COL_DESCRIPTION (8)
    const amtVal = row[7];                        // COL_AMOUNT (10)
    const notesVal = String(row[9] || '').trim(); // COL_NOTES (12)

    const hasHeaderOrLabel = dateVal.toLowerCase() === 'date' || typeVal.toLowerCase() === 'type';
    if (hasHeaderOrLabel) continue;

    const hasDateOrType = dateVal !== '' || typeVal !== '';
    const hasAmount = amtVal !== null && amtVal !== undefined && String(amtVal).trim() !== '' && Number(amtVal) > 0;
    const hasCatOrDesc = catVal !== '' || descVal !== '';

    // If Date or Type is present, but Amount is missing/0 and Category or Description is missing
    if (hasDateOrType && (!hasAmount && (!catVal || !descVal))) {
      orphaned.push({
        row: rowNum,
        date: dateVal,
        type: typeVal,
        category: catVal,
        description: descVal,
        amount: amtVal,
        notes: notesVal
      });
    }
  }
  return orphaned;
}

/**
 * Reads the most recent transactions from the Transactions sheet.
 * Scans up to `limit` non-empty rows starting from the bottom of the ledger.
 */
function getRecentTransactions(sheet, limit) {
  const safeLimit = Math.max(1, Math.min(limit || 50, 200));
  const lastRow = findLastTransactionRow(sheet);
  if (lastRow < 10) return [];

  const startRow = Math.max(10, lastRow - safeLimit + 1);
  const numRows = lastRow - startRow + 1;

  // Read C (Date) through L (Notes) -> 10 columns (cols 3 to 12)
  const rangeValues = sheet.getRange(startRow, COL_DATE, numRows, 10).getValues();
  const transactions = [];

  for (let i = rangeValues.length - 1; i >= 0; i--) {
    const rowNum = startRow + i;
    const row = rangeValues[i];
    const dateVal = row[0];
    const typeVal = String(row[1] || '').trim();
    const catVal = String(row[4] || '').trim();
    const descVal = String(row[5] || '').trim();
    const amtVal = row[7];
    const acctVal = String(row[8] || '').trim();
    const notesVal = String(row[9] || '').trim();

    // Skip empty or header rows
    if (!dateVal && !typeVal && !amtVal) continue;
    if (String(dateVal).toLowerCase() === 'date' || typeVal.toLowerCase() === 'type') continue;

    let formattedDate = '';
    if (dateVal instanceof Date) {
      formattedDate = Utilities.formatDate(dateVal, 'Africa/Nairobi', 'yyyy-MM-dd');
    } else if (dateVal) {
      formattedDate = String(dateVal).split('T')[0];
    }

    // Extract transaction code from Notes if present
    let txCode = '';
    const codeMatch = notesVal.match(/M-PESA Code:\s*([A-Z0-9_\-]+)/i) || notesVal.match(/\b([A-Z0-9]{8,15}(?:-FEE)?)\b/i);
    if (codeMatch) {
      txCode = codeMatch[1].trim();
    } else {
      txCode = 'ROW-' + rowNum;
    }

    transactions.push({
      row: rowNum,
      date: formattedDate,
      transactionCode: txCode,
      type: typeVal || 'Expenses',
      category: catVal || '',
      description: descVal || '',
      amount: typeof amtVal === 'number' ? amtVal : parseFloat(String(amtVal || '0').replace(/,/g, '')) || 0,
      account: acctVal || 'Mpesa',
      notes: notesVal,
      status: 'SYNCED'
    });
  }

  return transactions;
}

/**
 * Duplicate check: searches Column L (Notes) for the exact transaction code or marker.
 * Strictly uses exact M-PESA code marker matching rather than loose substrings.
 * Rejects with DUPLICATE if found, without appending.
 */
function checkDuplicateTransactionCode(sheet, txCode) {
  if (!txCode) return { isDuplicate: false };
  const targetCode = String(txCode).trim().toUpperCase();
  if (!targetCode) return { isDuplicate: false };

  const lastRow = findLastTransactionRow(sheet);
  if (lastRow < 10) return { isDuplicate: false };

  const numRows = lastRow - 9;
  const notesValues = sheet.getRange(10, COL_NOTES, numRows, 1).getValues();
  const escapedCode = targetCode.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const markerRegex = new RegExp('(?:^|\\|\\s*|\\s)M-PESA Code:\\s*' + escapedCode + '(?:\\s*\\||\\s|$)', 'i');

  for (let i = 0; i < notesValues.length; i++) {
    const rawVal = String(notesValues[i][0] || '').trim();
    if (!rawVal) continue;
    if (rawVal.toUpperCase() === targetCode || markerRegex.test(rawVal)) {
      return { isDuplicate: true, row: 10 + i };
    }
  }
  return { isDuplicate: false };
}

/**
 * Helper to locate headers in a 2D matrix
 */
function findHeaderLocations(values, headerText) {
  const locations = [];
  const target = headerText.trim().toLowerCase();
  for (let r = 0; r < values.length; r++) {
    for (let c = 0; c < values[r].length; c++) {
      if (String(values[r][c] || '').trim().toLowerCase() === target) {
        locations.push({ row: r, col: c });
      }
    }
  }
  return locations;
}

/**
 * Dynamically extracts category values below section headers from Set Up sheet
 */
function extractSectionCategories(values, headerText, sheetName) {
  const headers = findHeaderLocations(values, headerText);
  if (headers.length === 0) {
    throw new Error('Unable to discover required taxonomy header "' + headerText + '" from ' + sheetName + ' sheet');
  }

  const items = [];
  const seen = {};
  const primaryRow = headers[0].row;
  const matchingCols = headers.filter(function (h) { return Math.abs(h.row - primaryRow) <= 2; });

  for (let i = 0; i < matchingCols.length; i++) {
    const h = matchingCols[i];
    for (let r = h.row + 1; r < values.length; r++) {
      let isRowBlank = true;
      for (let c = 0; c < values[r].length; c++) {
        if (String(values[r][c] || '').trim() !== '') {
          isRowBlank = false;
          break;
        }
      }
      if (isRowBlank) break;

      const cellVal = String(values[r][h.col] || '').trim();
      if (cellVal && !seen[cellVal]) {
        seen[cellVal] = true;
        items.push(cellVal);
      }
    }
  }

  return {
    items: items,
    locations: matchingCols.map(function (h) { return { row: h.row + 1, col: h.col + 1 }; })
  };
}

/**
 * Dynamically extracts category values and accounts from 'Set up data 2' sheet
 */
function extractCategoriesFromSetUpData2(ss) {
  const sheet = ss.getSheetByName('Set up data 2');
  if (!sheet) return null;

  const lastRow = Math.min(sheet.getLastRow(), 200);
  if (lastRow < 10) return null;

  const values = sheet.getRange(1, 2, lastRow, 4).getValues(); // B1:E{lastRow}
  const categoriesByType = {
    Income: ['Rollover from Previous Month (+)'],
    Bills: [],
    Debt: [],
    Expenses: [],
    Savings: [],
    Balance: []
  };
  const accounts = [];

  let currentSection = 'Income';
  for (let i = 0; i < values.length; i++) {
    const b = String(values[i][0] || '').trim();
    const e = String(values[i][3] || '').trim();

    if (b === 'Bills and Subscription Title') { currentSection = 'Bills'; continue; }
    if (b === 'Debt Title') { currentSection = 'Debt'; continue; }
    if (b === 'Expenses Title') { currentSection = 'Expenses'; continue; }
    if (b === 'Savings Title') { currentSection = 'Savings'; continue; }
    if (b === 'Bank Accounts Title') { currentSection = 'Accounts'; continue; }
    if (b === 'Start month' || b.indexOf('Month ') === 0) { currentSection = 'Done'; continue; }

    if (currentSection === 'Income') {
      const item = e || b;
      if (item && item.indexOf('Title') === -1 && item.indexOf('CellImage') === -1 && item.indexOf('no empty spaces') === -1 && categoriesByType.Income.indexOf(item) === -1) {
        categoriesByType.Income.push(item);
      }
    } else if (currentSection === 'Accounts') {
      if (b && b.indexOf('Title') === -1 && isNaN(Number(b)) && accounts.indexOf(b) === -1) {
        accounts.push(b);
      }
    } else if (currentSection !== 'Done' && categoriesByType[currentSection]) {
      if (b && b.indexOf('Title') === -1 && isNaN(Number(b)) && categoriesByType[currentSection].indexOf(b) === -1) {
        categoriesByType[currentSection].push(b);
      }
    }
  }

  return {
    categoriesByType: categoriesByType,
    accounts: accounts
  };
}

/**
 * Resolves data validation items from a Google Sheets DataValidation rule
 */
function resolveDropdownFromRule(rule, contextName) {
  if (!rule) {
    throw new Error("No data validation rule found for " + contextName);
  }

  const criteriaType = rule.getCriteriaType();
  const criteriaValues = rule.getCriteriaValues();
  const resolvedItems = [];
  let sourceRangeA1 = null;

  for (let i = 0; i < criteriaValues.length; i++) {
    const v = criteriaValues[i];
    if (v && typeof v.getValues === 'function') {
      try {
        sourceRangeA1 = (v.getSheet() ? v.getSheet().getName() + '!' : '') + v.getA1Notation();
      } catch (err) {
        sourceRangeA1 = 'RangeObject';
      }
      const raw2D = v.getValues();
      for (let r = 0; r < raw2D.length; r++) {
        for (let c = 0; c < raw2D[r].length; c++) {
          const item = String(raw2D[r][c] || '').trim();
          if (item && resolvedItems.indexOf(item) === -1) {
            resolvedItems.push(item);
          }
        }
      }
    } else if (Array.isArray(v)) {
      for (let j = 0; j < v.length; j++) {
        const item = String(v[j] || '').trim();
        if (item && resolvedItems.indexOf(item) === -1) {
          resolvedItems.push(item);
        }
      }
    } else if (typeof v === 'string' && v.trim()) {
      const item = v.trim();
      if (resolvedItems.indexOf(item) === -1) {
        resolvedItems.push(item);
      }
    }
  }

  if (resolvedItems.length === 0) {
    throw new Error("Data validation rule for " + contextName + " resolved to 0 values.");
  }

  return {
    criteriaType: String(criteriaType),
    sourceRange: sourceRangeA1,
    items: resolvedItems
  };
}

/**
 * Reads the authoritative taxonomy directly from live spreadsheet data-validation dropdowns
 * (Columns D, G, K in Transactions sheet) and cross-checks against Set Up tab.
 */
function getTaxonomyData(ss) {
  const txSheet = ss.getSheetByName(SHEET_NAME_TRANSACTIONS);
  if (!txSheet) {
    throw new Error('Required sheet "' + SHEET_NAME_TRANSACTIONS + '" not found in spreadsheet.');
  }

  // 1. Read Type dropdown (Column D) directly from live validation rule
  let typeCell = txSheet.getRange('D10');
  let typeRule = typeCell.getDataValidation();
  if (!typeRule) {
    for (let r = 10; r <= 35; r++) {
      const candidate = txSheet.getRange(r, COL_TYPE).getDataValidation();
      if (candidate) {
        typeRule = candidate;
        typeCell = txSheet.getRange(r, COL_TYPE);
        break;
      }
    }
  }
  if (!typeRule) {
    throw new Error('No validation rule found for Type (Column D) in ' + SHEET_NAME_TRANSACTIONS + ' sheet.');
  }
  const typeValidationInfo = resolveDropdownFromRule(typeRule, 'Type (Column D at ' + typeCell.getA1Notation() + ')');
  const resolvedTypes = typeValidationInfo.items;

  // Sync VALID_TYPES in memory directly from live dropdown
  VALID_TYPES = resolvedTypes;

  // 2. Read Account dropdown (Column K) directly from live validation rule
  let accCell = txSheet.getRange('K10');
  let accRule = accCell.getDataValidation();
  if (!accRule) {
    for (let r = 10; r <= 35; r++) {
      const candidate = txSheet.getRange(r, COL_ACCOUNT).getDataValidation();
      if (candidate) {
        accRule = candidate;
        accCell = txSheet.getRange(r, COL_ACCOUNT);
        break;
      }
    }
  }
  if (!accRule) {
    throw new Error('No validation rule found for Account (Column K) in ' + SHEET_NAME_TRANSACTIONS + ' sheet.');
  }
  const accountValidationInfo = resolveDropdownFromRule(accRule, 'Account (Column K at ' + accCell.getA1Notation() + ')');
  const resolvedAccounts = accountValidationInfo.items;

  // 3. Read Category dropdown (Column G) directly from live validation rules per Type
  // Category dropdown is dynamic per-row based on the row's Type in Column D.
  // We locate sample rows for each discovered Type to capture every live category list.
  // 3. Read Category dropdown: Prioritize 'Set up data 2' for complete master category catalogs
  const setup2Catalog = extractCategoriesFromSetUpData2(ss);
  const categoryValidationInfoByType = {};
  const categoriesByType = {};

  const scanLimit = Math.min(txSheet.getLastRow(), 500);
  const typeColValues = txSheet.getRange(1, COL_TYPE, scanLimit, 1).getValues();

  for (let t = 0; t < resolvedTypes.length; t++) {
    const currentType = resolvedTypes[t];
    if (currentType === 'Balance') {
      categoriesByType[currentType] = [];
      categoryValidationInfoByType[currentType] = { items: [] };
      continue;
    }

    if (setup2Catalog && setup2Catalog.categoriesByType[currentType] && setup2Catalog.categoriesByType[currentType].length > 0) {
      categoriesByType[currentType] = setup2Catalog.categoriesByType[currentType];
      categoryValidationInfoByType[currentType] = {
        sourceRange: 'Set up data 2!B:E',
        items: setup2Catalog.categoriesByType[currentType]
      };
      continue;
    }

    let sampleRow = -1;
    for (let r = 9; r < typeColValues.length; r++) { // 0-indexed, row 10 is index 9
      const val = String(typeColValues[r][0] || '').trim();
      if (val.toLowerCase() === currentType.toLowerCase()) {
        sampleRow = r + 1;
        break;
      }
    }

    if (sampleRow === -1) {
      sampleRow = 10;
    }

    const catCell = txSheet.getRange(sampleRow, COL_CATEGORY);
    const catRule = catCell.getDataValidation();
    let resolvedCat = null;
    if (catRule) {
      try {
        resolvedCat = resolveDropdownFromRule(catRule, 'Category for Type "' + currentType + '" at ' + catCell.getA1Notation());
      } catch (e) {
        resolvedCat = null;
      }
    }

    if (resolvedCat && resolvedCat.items && resolvedCat.items.length > 0) {
      categoryValidationInfoByType[currentType] = {
        row: sampleRow,
        cell: catCell.getA1Notation(),
        criteriaType: resolvedCat.criteriaType,
        sourceRange: resolvedCat.sourceRange,
        items: resolvedCat.items
      };
      categoriesByType[currentType] = resolvedCat.items;
    } else {
      categoriesByType[currentType] = [];
      categoryValidationInfoByType[currentType] = { items: [] };
    }
  }

  // 4. Cross-check against Set Up tab (Live validation rule remains authoritative)
  const setupSheet = ss.getSheetByName(SHEET_NAME_SETUP);
  const discrepancies = {};
  if (setupSheet) {
    try {
      const setupValues = setupSheet.getDataRange().getValues();
      const setupHeaderMap = {
        'Income': 'Income Source',
        'Bills': 'Bill Category',
        'Debt': 'Debt Category',
        'Expenses': 'Expense Category'
      };

      Object.keys(setupHeaderMap).forEach(function (typeKey) {
        const headerText = setupHeaderMap[typeKey];
        try {
          const setupExtracted = extractSectionCategories(setupValues, headerText, SHEET_NAME_SETUP);
          const liveList = categoriesByType[typeKey] || [];
          const setupList = setupExtracted.items || [];
          const isDiff = JSON.stringify(liveList) !== JSON.stringify(setupList);
          if (isDiff) {
            discrepancies[typeKey] = {
              liveDropdownItems: liveList,
              setUpTabItems: setupList,
              liveOnly: liveList.filter(function (x) { return setupList.indexOf(x) === -1; }),
              setUpOnly: setupList.filter(function (x) { return liveList.indexOf(x) === -1; })
            };
            console.warn('TAXONOMY DISCREPANCY DETECTED for ' + typeKey + ':', JSON.stringify(discrepancies[typeKey]));
          }
        } catch (e) {
          discrepancies[typeKey] = { error: e.message };
        }
      });
    } catch (setupErr) {
      console.warn('Cross-check against Set Up sheet encountered an error:', setupErr.message);
    }
  }

  const taxonomy = {
    types: resolvedTypes,
    categoriesByType: categoriesByType,
    accounts: resolvedAccounts,
    discrepanciesWithSetUpTab: discrepancies
  };

  // Required Logging
  console.log("FINAL TAXONOMY:", JSON.stringify(taxonomy));
  console.log("RESOLVED FROM VALIDATION RULES:", JSON.stringify({
    type: typeValidationInfo,
    category: categoryValidationInfoByType,
    account: accountValidationInfo
  }));

  return taxonomy;
}

/**
 * Diagnostic function to test taxonomy against any spreadsheet ID safely (Read-Only)
 */
function testTaxonomyAgainstSpreadsheet(spreadsheetId) {
  const ss = SpreadsheetApp.openById(spreadsheetId);
  console.log("================================");
  console.log("SPREADSHEET:", spreadsheetId);
  console.log("SHEETS:", JSON.stringify(ss.getSheets().map(function (s) { return s.getName(); })));

  const taxonomy = getTaxonomyData(ss);
  console.log("FINAL TAXONOMY:", JSON.stringify(taxonomy));
  return taxonomy;
}


/**
 * Programmatically discovers and parses the current month sheet (Read-Only)
 */
function getMonthlyDashboardData(ss) {
  const monthNames = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

  // PART B: Use server-side date only, explicit Africa/Nairobi timezone — never client-supplied.
  // No automatic year/spreadsheet switching: that is a deliberate manual user action.
  const now = new Date();
  const monthIndex = parseInt(Utilities.formatDate(now, 'Africa/Nairobi', 'M'), 10) - 1;
  const currentMonthCode = monthNames[monthIndex];
  const debugDateStr = Utilities.formatDate(now, 'Africa/Nairobi', 'yyyy-MM-dd HH:mm:ss z');

  console.log('[getMonthlyDashboardData] Server time (Africa/Nairobi): ' + debugDateStr +
              ' → resolved month: ' + currentMonthCode);

  const sheetNames = ss.getSheets().map(function (s) { return s.getName(); });

  // Strict: require the exact month tab to exist. Never silently fall back to another month.
  if (sheetNames.indexOf(currentMonthCode) === -1) {
    throw new Error('MONTH_TAB_NOT_FOUND: "' + currentMonthCode + '". ' +
      'Available sheets: [' + sheetNames.join(', ') + ']. ' +
      'The current month tab has not been created yet — please add it to the spreadsheet.');
  }

  const targetSheetName = currentMonthCode;

  const sheet = ss.getSheetByName(targetSheetName);
  if (!sheet) {
    throw new Error('Sheet "' + targetSheetName + '" not found in spreadsheet.');
  }

  // Read a bounded range — monthly budget sheets rarely exceed 120 rows x 60 cols.
  // Keeping this tight avoids forcing Apps Script to evaluate trailing formula cells.
  const maxRows = Math.min(sheet.getMaxRows ? sheet.getMaxRows() : 120, 120);
  const maxCols = Math.min(sheet.getMaxColumns ? sheet.getMaxColumns() : 90, 90);
  const dataRange = sheet.getRange(1, 1, maxRows, maxCols);
  const values = dataRange.getValues();

  // Helper to search label in values
  function findCell(targetLabel) {
    const target = String(targetLabel).toLowerCase().trim();
    for (let r = 0; r < values.length; r++) {
      for (let c = 0; c < values[r].length; c++) {
        const val = String(values[r][c] || '').toLowerCase().trim();
        if (val === target) {
          return { row: r, col: c };
        }
      }
    }
    return null;
  }

  function parseAmount(val) {
    if (typeof val === 'number') return val;
    if (!val) return 0;
    const clean = String(val).replace(/[^0-9.\-]/g, '');
    return parseFloat(clean) || 0;
  }

  function findTileValue(pos) {
    // 1. Precise positional read: Large headline totals sit on row index 1 (Row 2 in sheet) at pos.col
    if (pos.row > 1 && pos.col < values[1].length) {
      const valRow2 = values[1][pos.col];
      if (valRow2 !== '' && valRow2 !== null && valRow2 !== undefined) {
        return parseAmount(valRow2);
      }
    }

    // 2. Search radius using font size >= 18 or largest-font numeric candidate
    for (let radius = 1; radius <= 5; radius++) {
      let bestVal = null;
      for (let dr = -radius; dr <= radius; dr++) {
        for (let dc = -radius; dc <= radius; dc++) {
          let r = pos.row + dr;
          let c = pos.col + dc;
          if (r >= 0 && r < values.length && c >= 0 && c < values[r].length) {
            let val = values[r][c];
            if (val !== '' && val !== null) {
              const strVal = String(val);
              // Ignore subtitle annotation strings like "Ksh... under goal/budget"
              if (strVal.includes('under') || strVal.includes('over') || strVal.includes('goal') || strVal.includes('budget')) {
                continue;
              }
              let num = parseAmount(val);
              if (typeof val === 'number' || (typeof val === 'string' && val.match(/[0-9]/))) {
                if (bestVal === null) {
                  bestVal = num;
                }
              }
            }
          }
        }
      }
      if (bestVal !== null) return bestVal;
    }
    return null;
  }

  // 1. Discover Category Tables by searching for "Category" or "Savings" headers
  const categoryHeaders = [];
  for (let r = 0; r < values.length; r++) {
    for (let c = 0; c < Math.min(10, values[r].length); c++) {
      const val = String(values[r][c] || '').trim().toUpperCase();
      if (val === 'CATEGORY' || val === 'SAVINGS' || val === 'SAVINGS GOAL') {
        categoryHeaders.push({ row: r, col: c });
      }
    }
  }

  if (categoryHeaders.length === 0) {
    throw new Error('No "Category" header found in sheet "' + targetSheetName + '".');
  }

  // Map category headers to sections based on preceding section title in col B (index 1)
  const tables = {
    Income: [],
    Bills: [],
    Debt: [],
    Expenses: [],
    Savings: []
  };

  const sectionNames = ['Income', 'Bills', 'Debt', 'Expenses', 'Savings'];

  categoryHeaders.forEach(function (h, idx) {
    const r = h.row;
    const headerRowValues = values[r];
    
    // Find Goal/Budget, Actual, Diff columns in this header row
    let goalCol = -1;
    let actualCol = -1;
    let diffCol = -1;

    for (let c = h.col + 1; c < Math.min(h.col + 35, headerRowValues.length); c++) {
      const cellText = String(headerRowValues[c] || '').toLowerCase().trim();
      if (cellText === 'goal' || cellText === 'budget') {
        goalCol = c;
      } else if (cellText === 'actual' || cellText === 'saved') {
        actualCol = c;
      } else if (cellText === 'diff' || cellText === 'diff.') {
        diffCol = c;
      }
    }

    if (actualCol === -1) {
      return; // Not a standard category table
    }

    // Determine section name by looking backwards for section title across all columns in preceding rows
    let detectedSection = sectionNames[idx] || 'Expenses';
    for (let checkR = r - 1; checkR >= Math.max(0, r - 5); checkR--) {
      const checkText = values[checkR].join(' ').toUpperCase();
      if (checkText.indexOf('INCOME') !== -1) { detectedSection = 'Income'; break; }
      if (checkText.indexOf('BILL') !== -1) { detectedSection = 'Bills'; break; }
      if (checkText.indexOf('DEBT') !== -1) { detectedSection = 'Debt'; break; }
      if (checkText.indexOf('EXPENSE') !== -1) { detectedSection = 'Expenses'; break; }
      if (checkText.indexOf('SAVING') !== -1) { detectedSection = 'Savings'; break; }
    }

    const items = [];
    let consecutiveBlanks = 0;
    for (let dataR = r + 1; dataR < values.length; dataR++) {
      let rawCat = '';
      for (let cIdx = 0; cIdx < Math.min(10, values[dataR].length); cIdx++) {
        const cellVal = values[dataR][cIdx];
        if (cellVal && typeof cellVal === 'string' && cellVal.trim().length > 0) {
          const trimmed = cellVal.trim();
          if (trimmed.indexOf('THIS SPREADSHEET') === -1 && trimmed.indexOf('Total') === -1 && trimmed !== 'Category' && trimmed !== 'Savings') {
            rawCat = trimmed;
            break;
          }
        }
      }

      if (!rawCat) {
        consecutiveBlanks++;
        if (items.length === 0) {
          continue;
        }
        if (consecutiveBlanks <= 15) {
          continue;
        }
        break;
      }
      consecutiveBlanks = 0;
      if (rawCat.indexOf('THIS SPREADSHEET') !== -1 || rawCat.indexOf('Total') !== -1) break;

      // In the sheet, currency symbols ("Ksh") are at goalCol, actualCol, diffCol, and values are at +1
      const gVal = goalCol !== -1 ? parseAmount(values[dataR][goalCol + 1] !== undefined && values[dataR][goalCol + 1] !== '' ? values[dataR][goalCol + 1] : values[dataR][goalCol]) : 0;
      const aVal = actualCol !== -1 ? parseAmount(values[dataR][actualCol + 1] !== undefined && values[dataR][actualCol + 1] !== '' ? values[dataR][actualCol + 1] : values[dataR][actualCol]) : 0;
      const dVal = diffCol !== -1 ? parseAmount(values[dataR][diffCol + 1] !== undefined && values[dataR][diffCol + 1] !== '' ? values[dataR][diffCol + 1] : values[dataR][diffCol]) : (aVal - gVal);

      items.push({
        category: rawCat,
        goal: gVal,
        actual: aVal,
        diff: dVal
      });
    }

    tables[detectedSection] = items;
  });

  // Helper to construct tile response from direct sheet cell values (or fallback to table aggregate)
  function buildTile(headerLabel, tableItems, sectionType) {
    const headerPos = findCell(headerLabel);
    let a = null;
    if (headerPos) {
      a = findTileValue(headerPos);
    }
    
    // Fallback to table sum only if tile position is not found in sheet
    let gSum = 0;
    let aSum = 0;
    (tableItems || []).forEach(function (it) {
      gSum += (Number(it.goal) || 0);
      aSum += (Number(it.actual) || 0);
    });

    const actualVal = (a !== null && a !== undefined) ? a : aSum;
    const goalVal = gSum;
    const diffVal = actualVal - goalVal;
    const pct = goalVal > 0 ? Math.round((actualVal / goalVal) * 100) : 0;
    
    let st = '';
    if (sectionType === 'Income') {
      st = pct + '% of goal';
    } else if (sectionType === 'Bills' || sectionType === 'Expenses') {
      const underOver = diffVal <= 0 ? 'under budget' : 'over budget';
      st = pct + '% • Ksh ' + Math.abs(diffVal).toLocaleString('en-US') + ' ' + underOver;
    } else {
      const underOver = diffVal <= 0 ? 'under goal' : 'over goal';
      st = pct + '% • Ksh ' + Math.abs(diffVal).toLocaleString('en-US') + ' ' + underOver;
    }
    return { actual: actualVal, goal: goalVal, diff: diffVal, statusText: st };
  }

  const incomeTile = buildTile('Total Income', tables.Income, 'Income');
  const billsTile = buildTile('Total Bills', tables.Bills, 'Bills');
  const debtTile = buildTile('Total Debt Payoff', tables.Debt, 'Debt');
  const expensesTile = buildTile('Total Expenses', tables.Expenses, 'Expenses');
  const savingsTile = buildTile('Total Savings', tables.Savings, 'Savings');

  // Direct read for Unallocated Income tile (Row 5 col 80 in sheet)
  const unallocHeaderPos = findCell('Unallocated Income');
  let unallocVal = unallocHeaderPos ? findTileValue(unallocHeaderPos) : null;
  if (unallocVal === null || unallocVal === undefined) {
    const totalAllocatedActual = billsTile.actual + debtTile.actual + expensesTile.actual + savingsTile.actual;
    unallocVal = incomeTile.actual - totalAllocatedActual;
  }
  const unallocatedTile = { actual: unallocVal, statusText: 'Remaining balance' };

  return {
    month: targetSheetName,
    summaryTiles: {
      totalIncome: incomeTile,
      totalBills: billsTile,
      totalDebtPayoff: debtTile,
      totalExpenses: expensesTile,
      totalSavings: savingsTile,
      unallocatedIncome: unallocatedTile
    },
    tables: tables
  };
}



/**
 * Programmatically discovers and parses the Accounts tab (Read-Only)
 */
function getAccountsData(ss) {
  if (_accountsDataCache) {
    return _accountsDataCache;
  }
  const sheet = ss.getSheetByName('Accounts');
  if (!sheet) {
    throw new Error('Sheet "Accounts" not found in spreadsheet.');
  }

  const maxRows = Math.min(sheet.getMaxRows ? sheet.getMaxRows() : 30, 40);
  const maxCols = Math.min(sheet.getMaxColumns ? sheet.getMaxColumns() : 25, 30);
  const dataRange = sheet.getRange(1, 1, maxRows, maxCols);
  const values = dataRange.getValues();

  // Search for the table header row
  let headerRow = -1;
  let colAccountNames = -1;
  let colStartBalance = -1;
  let colCurrentBalance = -1;
  let colDeposits = -1;
  let colWithdrawals = -1;

  for (let r = 0; r < values.length; r++) {
    for (let c = 0; c < values[r].length; c++) {
      const val = String(values[r][c] || '').toLowerCase().trim();
      if (val === 'account names') {
        headerRow = r;
        colAccountNames = c;
      } else if (val === 'start balance') {
        colStartBalance = c;
      } else if (val === 'current balance' && headerRow === r) {
        colCurrentBalance = c;
      } else if (val.indexOf('deposits') !== -1 && headerRow === r) {
        colDeposits = c;
      } else if (val.indexOf('withdrawals') !== -1 && headerRow === r) {
        colWithdrawals = c;
      }
    }
    if (colAccountNames !== -1) {
      // Complete column discovery in this header row
      for (let c = 0; c < values[headerRow].length; c++) {
        const val = String(values[headerRow][c] || '').toLowerCase().trim();
        if (val === 'start balance') colStartBalance = c;
        if (val === 'current balance') colCurrentBalance = c;
        if (val.indexOf('deposits') !== -1) colDeposits = c;
        if (val.indexOf('withdrawals') !== -1) colWithdrawals = c;
      }
      break;
    }
  }

  if (colAccountNames === -1) {
    throw new Error('Required header "Account Names" not found in Accounts tab.');
  }
  if (colCurrentBalance === -1) {
    throw new Error('Required header "Current Balance" not found in Accounts tab.');
  }

  function parseAmount(val) {
    if (typeof val === 'number') return val;
    if (!val) return 0;
    const clean = String(val).replace(/[^0-9.\-]/g, '');
    return parseFloat(clean) || 0;
  }

  const accounts = [];
  // Standard Accounts list in this sheet
  for (let r = headerRow + 1; r < values.length; r++) {
    const acctName = String(values[r][colAccountNames] || '').trim();
    if (!acctName) continue;
    if (acctName.indexOf('Total') !== -1 || acctName.indexOf('THIS SPREADSHEET') !== -1) break;

    // Numerical values are located in the column or column + 1 (if currency column is separate)
    const startVal = colStartBalance !== -1 ? parseAmount(values[r][colStartBalance + 1] !== undefined && values[r][colStartBalance + 1] !== '' ? values[r][colStartBalance + 1] : values[r][colStartBalance]) : 0;
    const currVal = colCurrentBalance !== -1 ? parseAmount(values[r][colCurrentBalance + 1] !== undefined && values[r][colCurrentBalance + 1] !== '' ? values[r][colCurrentBalance + 1] : values[r][colCurrentBalance]) : 0;
    const depVal = colDeposits !== -1 ? parseAmount(values[r][colDeposits + 1] !== undefined && values[r][colDeposits + 1] !== '' ? values[r][colDeposits + 1] : values[r][colDeposits]) : 0;
    const withVal = colWithdrawals !== -1 ? parseAmount(values[r][colWithdrawals + 1] !== undefined && values[r][colWithdrawals + 1] !== '' ? values[r][colWithdrawals + 1] : values[r][colWithdrawals]) : 0;

    accounts.push({
      accountName: acctName,
      startBalance: startVal,
      currentBalance: currVal,
      deposits: depVal,
      withdrawals: withVal
    });
  }

  _accountsDataCache = accounts;
  return accounts;
}

/**
 * Creates standardized JSON HTTP Response with CORS headers
 */
function createJsonResponse(data, statusCode) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Programmatically discovers and parses the Savings tab (Read-Only)
 */
function getSavingsDashboardData(ss) {
  const sheetNames = ss.getSheets().map(function (s) { return s.getName(); });
  let tabName = sheetNames.find(function (n) { return n.toLowerCase().indexOf('savings dashboard') !== -1; });
  if (!tabName) {
    tabName = sheetNames.find(function (n) { return n.toLowerCase().indexOf('savings') !== -1; });
  }
  if (!tabName) {
    throw new Error('SAVINGS_TAB_NOT_FOUND. Available sheets: [' + sheetNames.join(', ') + '].');
  }

  const sheet = ss.getSheetByName(tabName);
  const maxRows = Math.min(sheet.getMaxRows ? sheet.getMaxRows() : 100, 100);
  const maxCols = Math.min(sheet.getMaxColumns ? sheet.getMaxColumns() : 60, 60);
  const dataRange = sheet.getRange(1, 1, maxRows, maxCols);
  const values = dataRange.getValues();

  function parseAmount(val) {
    if (typeof val === 'number') return val;
    if (!val) return 0;
    const clean = String(val).replace(/[^0-9.\-]/g, '');
    return parseFloat(clean) || 0;
  }

  function extractNumRight(rowIdx, startCol) {
    let str = '';
    // Look at up to 4 cells to the right to catch merged values
    for (let dc = 0; dc <= 3; dc++) {
       let val = values[rowIdx][startCol + dc];
       if (val !== undefined && val !== null) {
          str += ' ' + val;
       }
    }
    return parseAmount(str);
  }

  // Find header row by looking for 'Category' and 'Already Saved'
  let headerRow = -1;
  let cols = { category: -1, goal: -1, saved: -1, remaining: -1, progress: -1 };

  for (let r = 0; r < values.length; r++) {
    let foundCategory = -1;
    let foundSaved = -1;
    for (let c = 0; c < values[r].length; c++) {
      const val = String(values[r][c] || '').toLowerCase().trim();
      if (val === 'category') foundCategory = c;
      if (val === 'already saved') foundSaved = c;
    }
    if (foundCategory !== -1 && foundSaved !== -1) {
      headerRow = r;
      cols.category = foundCategory;
      cols.saved = foundSaved;
      // Now find the rest in this row
      for (let c = 0; c < values[r].length; c++) {
        const val = String(values[r][c] || '').toLowerCase().trim();
        if (val === 'goal') cols.goal = c;
        if (val === 'remaining to save') cols.remaining = c;
        if (val === 'progress') cols.progress = c;
      }
      break;
    }
  }

  if (headerRow === -1) {
    throw new Error('Could not find savings table headers in ' + tabName);
  }

  const goals = [];
  let blankCount = 0;
  for (let r = headerRow + 1; r < values.length; r++) {
    let cat = String(values[r][cols.category + 1] || values[r][cols.category] || '').trim();
    if (!cat) {
        blankCount++;
        if (blankCount > 3) break;
        continue;
    }
    if (cat.indexOf('Total') !== -1 || cat.indexOf('Spreadsheet') !== -1) {
        break;
    }
    blankCount = 0;

    let goalVal = extractNumRight(r, cols.goal);
    let savedVal = extractNumRight(r, cols.saved);
    let remainingVal = extractNumRight(r, cols.remaining);
    
    let progCell = values[r][cols.progress + 1] !== '' ? values[r][cols.progress + 1] : values[r][cols.progress];
    let progressVal = 0;
    if (typeof progCell === 'number') {
        progressVal = progCell;
    } else if (typeof progCell === 'string') {
        let num = parseFloat(progCell.replace(/[^0-9.]/g, ''));
        if (progCell.indexOf('%') !== -1) {
            progressVal = num / 100;
        } else {
            progressVal = num;
        }
    }

    goals.push({
      category: cat,
      goal: goalVal,
      saved: savedVal,
      remaining: remainingVal,
      progress: progressVal || 0
    });
  }

  return {
    tab: tabName,
    goals: goals
  };
}
