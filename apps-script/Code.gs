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

const SCRIPT_VERSION = '2026.09.08.v8_spreadsheet_grounded';
const SCRIPT_BUILD_ID = 'GMD_GAS_20260908_GROUNDED_01';

let VALID_TYPES = ['Income', 'Expenses', 'Bills', 'Debt', 'Savings', 'Balance'];


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

  // Write strictly to safe raw transaction write boundaries:
  // 1. Range C:D (Date, Type)
  sheet.getRange(targetRow, COL_DATE, 1, 2).setValues([[
    formattedDate,
    tx.type
  ]]);
  SpreadsheetApp.flush();

  // 2. Range G:H (Category, Description)
  const catCell = sheet.getRange(targetRow, COL_CATEGORY);
  const catRule = catCell.getDataValidation();
  try {
    sheet.getRange(targetRow, COL_CATEGORY, 1, 2).setValues([[
      tx.category,
      tx.description
    ]]);
  } catch (gErr) {
    // If dynamic dependent validation in X:AU has not finished recalculating,
    // write value safely and restore validation rule on cell
    if (catRule) catCell.clearDataValidations();
    sheet.getRange(targetRow, COL_CATEGORY, 1, 2).setValues([[
      tx.category,
      tx.description
    ]]);
    if (catRule) catCell.setDataValidation(catRule);
  }

  // 3. Range J:L (Amount, Account, Notes)
  // CRITICAL: Column I (currency formula 'Set Up'!$C$10) is preserved and NEVER overwritten!
  // Columns A:B, E:F (F has VLOOKUP formula), and P:BR are also preserved.
  const accCell = sheet.getRange(targetRow, COL_ACCOUNT);
  const accRule = accCell.getDataValidation();
  try {
    sheet.getRange(targetRow, COL_AMOUNT, 1, 3).setValues([[
      Number(tx.amount),
      tx.account,
      formattedNotes
    ]]);
  } catch (kErr) {
    if (accRule) accCell.clearDataValidations();
    sheet.getRange(targetRow, COL_AMOUNT, 1, 3).setValues([[
      Number(tx.amount),
      tx.account,
      formattedNotes
    ]]);
    if (accRule) accCell.setDataValidation(accRule);
  }

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
      notes: formattedNotes,
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

  if (!tx.transactionCode || typeof tx.transactionCode !== 'string' || tx.transactionCode.trim().length < 8 || tx.transactionCode.trim().length > 12) {
    errors.push('Valid transactionCode is required (8-12 characters)');
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
 * Creates standardized JSON HTTP Response with CORS headers
 */
function createJsonResponse(data, statusCode) {
  const output = ContentService.createTextOutput(JSON.stringify(data));
  output.setMimeType(ContentService.MimeType.JSON);
  return output;
}
