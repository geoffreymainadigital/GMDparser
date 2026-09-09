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

const SCRIPT_VERSION = '2026.09.09.v15_no_flush';
const SCRIPT_BUILD_ID = 'GMD_GAS_20260909_PROD_15';

let VALID_TYPES = ['Income', 'Expenses', 'Bills', 'Debt', 'Savings', 'Balance'];

// Module-level taxonomy cache — populated lazily on first write so each deployment
// pays the 'Set up data 2' read cost only once per Apps Script instance.
let _taxonomyCache = null;

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
 * Handle HTTP GET Requests (Health check and taxonomy)
 */
function doGet(e) {
  try {
    const action = (e && e.parameter && e.parameter.action) || 'health';
    const ss = SpreadsheetApp.getActiveSpreadsheet();

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
      const monthData = getMonthlyDashboardData(targetSs);
      const accountsData = getAccountsData(targetSs);
      return createJsonResponse({
        success: true,
        month: monthData,
        accounts: accountsData,
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

    // Verify Auth Secret if configured in Script Properties
    const scriptProperties = PropertiesService.getScriptProperties();
    const configuredSecret = scriptProperties.getProperty('GMD_AUTH_SECRET');
    if (configuredSecret) {
      const clientAuthKey = (payload && payload.authKey) ||
        (e && e.headers && (e.headers['X-GMD-Auth-Key'] || e.headers['x-gmd-auth-key']));
      if (clientAuthKey !== configuredSecret) {
        return createJsonResponse({
          success: false,
          status: 'UNAUTHORIZED',
          error: 'Unauthorized: Invalid or missing API key'
        }, 401);
      }
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
  if (isNaN(amount) || amount <= 0) {
    errors.push('Amount must be a positive non-zero number');
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
  const now = new Date();
  const monthIndex = parseInt(Utilities.formatDate(now, 'Africa/Nairobi', 'M'), 10) - 1;
  const currentMonthCode = monthNames[monthIndex];

  const sheetNames = ss.getSheets().map(function (s) { return s.getName(); });
  let targetSheetName = currentMonthCode;
  if (sheetNames.indexOf(targetSheetName) === -1) {
    const found = sheetNames.find(function (n) { return monthNames.indexOf(n.toUpperCase().trim()) !== -1; });
    if (!found) {
      throw new Error('Could not dynamically find any monthly budget sheet (searched: ' + monthNames.join(', ') + ')');
    }
    targetSheetName = found;
  }

  const sheet = ss.getSheetByName(targetSheetName);
  if (!sheet) {
    throw new Error('Sheet "' + targetSheetName + '" not found in spreadsheet.');
  }

  const maxRows = Math.min(sheet.getMaxRows ? sheet.getMaxRows() : 160, 200);
  const maxCols = Math.min(sheet.getMaxColumns ? sheet.getMaxColumns() : 90, 100);
  const dataRange = sheet.getRange(1, 1, maxRows, maxCols);
  const values = dataRange.getValues();
  const displayValues = dataRange.getDisplayValues();

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

  // 1. Locate summary tiles (Strict header search)
  const requiredTileLabels = [
    'Total Bills',
    'Total Debt Payoff',
    'Total Expenses',
    'Total Savings',
    'Unallocated Income'
  ];

  const tileCoords = {};
  requiredTileLabels.forEach(function (lbl) {
    const loc = findCell(lbl);
    if (!loc) {
      throw new Error('Required dashboard summary tile label "' + lbl + '" was not found in sheet "' + targetSheetName + '".');
    }
    tileCoords[lbl] = loc;
  });

  function parseAmount(val) {
    if (typeof val === 'number') return val;
    if (!val) return 0;
    const clean = String(val).replace(/[^0-9.\-]/g, '');
    return parseFloat(clean) || 0;
  }

  function getTileData(loc, isUnderLabel) {
    let actual = 0;
    let goal = 0;
    let diff = 0;
    let percent = 0;
    let status = '';

    if (loc) {
      const r = loc.row;
      const c = loc.col;
      // Actual amount is 2 rows above the label for bills/debt/expenses/savings
      const actualRow = isUnderLabel ? (r + 1) : Math.max(0, r - 2);
      actual = parseAmount(values[actualRow] && values[actualRow][c]);

      // Row r - 3 has goal/budget (Row 1 in 1-based index)
      if (!isUnderLabel && r >= 3) {
        goal = parseAmount(values[r - 3] && values[r - 3][c]);
      }

      // Status indicator row (e.g. Row 6 in 1-based index = r + 2)
      if (r + 2 < values.length) {
        status = String(displayValues[r + 2][c] || '').trim();
        if (!status && c > 0) {
          status = String(displayValues[r + 2][c + 1] || displayValues[r + 2][c - 1] || '').trim();
        }
      }
    }
    return { actual: actual, goal: goal, diff: actual - goal, statusText: status };
  }

  const billsTile = getTileData(tileCoords['Total Bills'], false);
  const debtTile = getTileData(tileCoords['Total Debt Payoff'], false);
  const expensesTile = getTileData(tileCoords['Total Expenses'], false);
  const savingsTile = getTileData(tileCoords['Total Savings'], false);

  // Unallocated income tile
  const unallocatedLoc = tileCoords['Unallocated Income'];
  const unallocatedActual = parseAmount(values[unallocatedLoc.row + 2] && values[unallocatedLoc.row + 2][unallocatedLoc.col]);

  // 2. Discover Category Tables by searching for "Category" headers
  const categoryHeaders = [];
  for (let r = 0; r < values.length; r++) {
    for (let c = 0; c < Math.min(10, values[r].length); c++) {
      const val = String(values[r][c] || '').trim();
      if (val === 'Category') {
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
      } else if (cellText === 'actual') {
        actualCol = c;
      } else if (cellText === 'diff' || cellText === 'diff.') {
        diffCol = c;
      }
    }

    if (actualCol === -1) {
      return; // Not a standard category table
    }

    // Determine section name by looking backwards for section title
    let detectedSection = sectionNames[idx] || 'Expenses';
    for (let checkR = r - 1; checkR >= Math.max(0, r - 5); checkR--) {
      const checkText = String(values[checkR][1] || values[checkR][0] || '').toUpperCase();
      if (checkText.indexOf('INCOME') !== -1) { detectedSection = 'Income'; break; }
      if (checkText.indexOf('BILL') !== -1) { detectedSection = 'Bills'; break; }
      if (checkText.indexOf('DEBT') !== -1) { detectedSection = 'Debt'; break; }
      if (checkText.indexOf('EXPENSE') !== -1) { detectedSection = 'Expenses'; break; }
      if (checkText.indexOf('SAVING') !== -1) { detectedSection = 'Savings'; break; }
    }

    const items = [];
    // Category values are located in col index 3 (Column D) right under Category (Column C, index 2)
    const catValCol = h.col + 1;
    for (let dataR = r + 1; dataR < values.length; dataR++) {
      const rawCat = String(values[dataR][catValCol] || values[dataR][h.col] || '').trim();
      if (!rawCat) break;
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

  // Calculate Total Income summary directly from Income table items
  let totalIncomeGoal = 0;
  let totalIncomeActual = 0;
  tables.Income.forEach(function (item) {
    totalIncomeGoal += item.goal;
    totalIncomeActual += item.actual;
  });

  const incomeTile = {
    actual: totalIncomeActual,
    goal: totalIncomeGoal,
    diff: totalIncomeActual - totalIncomeGoal,
    statusText: Math.round((totalIncomeActual / (totalIncomeGoal || 1)) * 100) + '% of goal'
  };

  return {
    month: targetSheetName,
    summaryTiles: {
      totalIncome: incomeTile,
      totalBills: billsTile,
      totalDebtPayoff: debtTile,
      totalExpenses: expensesTile,
      totalSavings: savingsTile,
      unallocatedIncome: { actual: unallocatedActual }
    },
    tables: tables
  };
}

/**
 * Programmatically discovers and parses the Accounts tab (Read-Only)
 */
function getAccountsData(ss) {
  const sheet = ss.getSheetByName('Accounts');
  if (!sheet) {
    throw new Error('Sheet "Accounts" not found in spreadsheet.');
  }

  const maxRows = Math.min(sheet.getMaxRows ? sheet.getMaxRows() : 50, 60);
  const maxCols = Math.min(sheet.getMaxColumns ? sheet.getMaxColumns() : 50, 60);
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

  return accounts;
}

/**
 * Creates standardized JSON HTTP Response with CORS headers
 */
function createJsonResponse(data, statusCode) {
  const output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}
