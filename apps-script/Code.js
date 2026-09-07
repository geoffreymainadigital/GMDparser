/**
 * GMDParser Google Apps Script Backend
 * Authoritative write layer & validation engine for Google Sheets
 */

// Configuration Constants
const SHEET_NAME_TRANSACTIONS = 'Transactions';
const SHEET_NAME_SETUP = 'Set up data 2';

// Column Indices in Transactions Sheet (1-based)
const COL_DATE = 3;             // C
const COL_TYPE = 4;             // D
const COL_CATEGORY = 7;         // G
const COL_DESCRIPTION = 8;      // H
const COL_AMOUNT = 10;          // J
const COL_ACCOUNT = 11;         // K
const COL_TX_CODE = 12;         // L

const VALID_TYPES = ['Income', 'Expenses', 'Bills', 'Debt', 'Savings', 'Transfer'];

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
        spreadsheetId: ss.getId(),
        sheetName: SHEET_NAME_TRANSACTIONS,
        sheetExists: isSheetReady,
        timestamp: new Date().toISOString()
      }, 200);
    }

    if (action === 'taxonomy') {
      const taxonomy = getTaxonomyData(ss);
      return createJsonResponse({
        success: true,
        data: taxonomy,
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

  // Duplicate Check against Column L (Transaction Code)
  const duplicateCheck = checkDuplicateTransactionCode(sheet, tx.transactionCode);
  if (duplicateCheck.isDuplicate) {
    return createJsonResponse({
      success: false,
      status: 'DUPLICATE_TRANSACTION_CODE',
      error: 'Transaction code ' + tx.transactionCode + ' already exists at row ' + duplicateCheck.row,
      existingRecord: {
        row: duplicateCheck.row,
        transactionCode: tx.transactionCode
      }
    }, 409);
  }

  // Find next available row safely
  const targetRow = findNextAvailableRow(sheet);

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

  // Write strictly to permitted boundaries:
  // Range C:D (Date, Type)
  sheet.getRange(targetRow, COL_DATE, 1, 2).setValues([[
    formattedDate,
    tx.type
  ]]);

  // Range G:H (Category, Description)
  sheet.getRange(targetRow, COL_CATEGORY, 1, 2).setValues([[
    tx.category,
    tx.description
  ]]);

  // Range J:L (Amount, Account, Transaction Code)
  sheet.getRange(targetRow, COL_AMOUNT, 1, 3).setValues([[
    Number(tx.amount),
    tx.account,
    tx.transactionCode.trim().toUpperCase()
  ]]);

  // Flush writes immediately to guarantee persistence
  SpreadsheetApp.flush();

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
      destinationAccount: tx.destinationAccount || null,
      timestamp: new Date().toISOString()
    }
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
        status: 'DUPLICATE_TRANSACTION_CODE',
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

  if (!tx.transactionCode || typeof tx.transactionCode !== 'string' || tx.transactionCode.trim().length < 8) {
    errors.push('Valid transactionCode is required (min 8 characters)');
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

  if (!tx.category || typeof tx.category !== 'string' || tx.category.trim() === '') {
    errors.push('Non-empty category is required');
  }

  if (!tx.account || typeof tx.account !== 'string' || tx.account.trim() === '') {
    errors.push('Non-empty source account is required');
  }

  if (!tx.description || typeof tx.description !== 'string' || tx.description.trim() === '') {
    errors.push('Non-empty description is required');
  }

  if (tx.type === 'Transfer') {
    if (!tx.destinationAccount || typeof tx.destinationAccount !== 'string' || tx.destinationAccount.trim() === '') {
      errors.push('Transfer transactions require a non-empty destinationAccount');
    }
  }

  return {
    isValid: errors.length === 0,
    errors: errors
  };
}

/**
 * Duplicate check on Column L (Transaction Code)
 */
function checkDuplicateTransactionCode(sheet, txCode) {
  if (!txCode) return { isDuplicate: false };
  const targetCode = txCode.trim().toUpperCase();
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return { isDuplicate: false };

  // Fetch Column L values
  const codes = sheet.getRange(1, COL_TX_CODE, lastRow, 1).getValues();
  for (let i = 0; i < codes.length; i++) {
    const val = codes[i][0];
    if (val && String(val).trim().toUpperCase() === targetCode) {
      return { isDuplicate: true, row: i + 1 };
    }
  }
  return { isDuplicate: false };
}

/**
 * Finds next empty row by inspecting columns C and L
 */
function findNextAvailableRow(sheet) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) {
    return 2; // Row 1 is header, first data row is 2
  }

  // Scan columns C and L from bottom or row 2 downwards
  const values = sheet.getRange(1, COL_DATE, lastRow, 1).getValues();
  for (let r = 1; r < values.length; r++) { // 0-indexed: row 2 is index 1
    const cellValue = values[r][0];
    if (!cellValue || String(cellValue).trim() === '') {
      // Confirm column L is also empty
      const codeValue = sheet.getRange(r + 1, COL_TX_CODE).getValue();
      if (!codeValue || String(codeValue).trim() === '') {
        return r + 1;
      }
    }
  }

  return lastRow + 1;
}

/**
 * Extracts taxonomy from setup sheet or returns baseline defaults
 */
function getTaxonomyData(ss) {
  const fallback = {
    types: VALID_TYPES,
    categoriesByType: {
      'Income': ['Salary', 'Business Income', 'Dividends', 'Interest', 'Refunds', 'Gifts / Support'],
      'Expenses': ['Groceries', 'Dining Out / Takeout', 'Transport & Fuel', 'Shopping & Clothing', 'Entertainment', 'Personal Care', 'Health & Pharmacy'],
      'Bills': ['Rent', 'Electricity / KPLC', 'Water', 'Internet / WiFi', 'TV & Subscriptions', 'Home Maintenance'],
      'Debt': ['Credit Card', 'Bank Loan Repayment', 'Hustler Fund', 'Personal Loan', 'Mobile Loan (M-Shwari / Fuliza)'],
      'Savings': ['Emergency Fund', 'Money Market Fund (MMF)', 'SACCO Monthly Deposit', 'Fixed Deposit', 'Treasury Bills'],
      'Transfer': ['Internal Account Transfer']
    },
    accounts: ['M-PESA', 'Bank (NCBA Loop)', 'Bank (Equity)', 'Bank (KCB)', 'SACCO Account', 'MMF Account', 'Cash']
  };

  const setupSheet = ss.getSheetByName(SHEET_NAME_SETUP);
  if (!setupSheet) {
    return fallback;
  }

  try {
    // Read categories from Setup Data 2 if populated
    // Income: E6:E23, Bills: B27:B66, Debt: B70:B89, Expenses: B93:B122, Savings: B126:B145
    const readRangeValues = function(rangeA1) {
      const vals = setupSheet.getRange(rangeA1).getValues();
      return vals.map(function(row) { return row[0]; })
                 .filter(function(v) { return v && String(v).trim() !== ''; })
                 .map(function(v) { return String(v).trim(); });
    };

    const incomeCats = readRangeValues('E6:E23');
    const billsCats = readRangeValues('B27:B66');
    const debtCats = readRangeValues('B70:B89');
    const expensesCats = readRangeValues('B93:B122');
    const savingsCats = readRangeValues('B126:B145');

    return {
      types: VALID_TYPES,
      categoriesByType: {
        'Income': incomeCats.length > 0 ? incomeCats : fallback.categoriesByType['Income'],
        'Expenses': expensesCats.length > 0 ? expensesCats : fallback.categoriesByType['Expenses'],
        'Bills': billsCats.length > 0 ? billsCats : fallback.categoriesByType['Bills'],
        'Debt': debtCats.length > 0 ? debtCats : fallback.categoriesByType['Debt'],
        'Savings': savingsCats.length > 0 ? savingsCats : fallback.categoriesByType['Savings'],
        'Transfer': ['Internal Account Transfer']
      },
      accounts: fallback.accounts
    };
  } catch (err) {
    console.warn('Failed reading setup sheet taxonomy, using baseline fallback: ' + err.message);
    return fallback;
  }
}

/**
 * Creates standardized JSON HTTP Response with CORS headers
 */
function createJsonResponse(data, statusCode) {
  const output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}
