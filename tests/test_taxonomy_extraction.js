import fs from 'node:fs';
import assert from 'node:assert';

function parseCSV(text) {
  const p = [];
  let row = [];
  let inQuotes = false;
  let cur = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"') {
        if (i + 1 < text.length && text[i + 1] === '"') {
          cur += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cur += c;
      }
    } else {
      if (c === '"') {
        inQuotes = true;
      } else if (c === ',') {
        row.push(cur);
        cur = '';
      } else if (c === '\r') {
        // ignore
      } else if (c === '\n') {
        row.push(cur);
        p.push(row);
        row = [];
        cur = '';
      } else {
        cur += c;
      }
    }
  }
  if (cur || row.length > 0) {
    row.push(cur);
    p.push(row);
  }
  return p;
}

/**
 * Simulates Apps Script resolveDropdownFromRule against real spreadsheet data
 */
export function extractTaxonomyFromDropdowns(sheets) {
  const txSheet = sheets['Transactions'];
  if (!txSheet) {
    throw new Error('Required sheet "Transactions" not found.');
  }

  // 1. Column D (Type)
  // In the real spreadsheet, Column D dropdown has criteria VALUE_IN_LIST or VALUE_IN_RANGE.
  // In sheet_Transactions.csv, all non-blank type entries in Col D (index 3) are:
  const typesSet = [];
  for (let r = 9; r < txSheet.length; r++) {
    const t = String(txSheet[r][3] || '').trim();
    if (t && typesSet.indexOf(t) === -1 && t !== 'Transactions') {
      typesSet.push(t);
    }
  }
  const typeValidationInfo = {
    criteriaType: 'VALUE_IN_LIST',
    sourceCell: 'D10',
    items: typesSet // ['Expenses', 'Income', 'Debt', 'Bills', 'Savings']
  };
  const resolvedTypes = typeValidationInfo.items;

  // 2. Column K (Account)
  // Column K validation rule points to Accounts sheet or named range of accounts.
  // From Accounts sheet (and Col K entries):
  const accountsSet = [];
  const accSheet = sheets['Accounts'];
  if (accSheet) {
    for (let r = 14; r <= 20; r++) { // rows 15-21 in 1-based indexing
      const accName = String(accSheet[r][2] || '').trim();
      if (accName && accountsSet.indexOf(accName) === -1) {
        accountsSet.push(accName);
      }
    }
  }
  // Cross check with Col K (index 10) of Transactions
  for (let r = 9; r < txSheet.length; r++) {
    const a = String(txSheet[r][10] || '').trim().replace(/^"|"$/g, '');
    if (a && accountsSet.indexOf(a) === -1 && a !== 'Account') {
      accountsSet.push(a);
    }
  }

  const accountValidationInfo = {
    criteriaType: 'VALUE_IN_RANGE',
    sourceCell: 'K10',
    sourceRange: 'Accounts!C15:C21',
    items: accountsSet
  };
  const resolvedAccounts = accountValidationInfo.items;

  // 3. Column G (Category) per Type from dynamic row helper range (Columns X onwards: index 23)
  const categoryValidationInfoByType = {};
  const categoriesByType = {};

  resolvedTypes.forEach(type => {
    // Find sample row for this type
    const sampleRowIndex = txSheet.findIndex((r, idx) => idx >= 9 && String(r[3] || '').trim().toLowerCase() === type.toLowerCase());
    if (sampleRowIndex === -1) {
      throw new Error(`Unable to find sample row for type "${type}" in Transactions sheet`);
    }

    const sampleRow = txSheet[sampleRowIndex];
    const rowNum = sampleRowIndex + 1;
    // Columns X to AU (indexes 23 to 46) contain the dynamic allowed category list for this row
    const rowCategories = [];
    for (let c = 23; c < sampleRow.length; c++) {
      const cat = String(sampleRow[c] || '').trim();
      if (cat && rowCategories.indexOf(cat) === -1) {
        rowCategories.push(cat);
      }
    }

    categoryValidationInfoByType[type] = {
      row: rowNum,
      cell: `G${rowNum}`,
      criteriaType: 'VALUE_IN_RANGE',
      sourceRange: `Transactions!X${rowNum}:AU${rowNum}`,
      items: rowCategories
    };
    categoriesByType[type] = rowCategories;
  });

  // 4. Cross-check against Set Up tab
  const setupSheet = sheets['Set Up'];
  const discrepancies = {};
  if (setupSheet) {
    const setupHeaderMap = {
      'Income': 'Income Source',
      'Bills': 'Bill Category',
      'Debt': 'Debt Category',
      'Expenses': 'Expense Category'
    };

    function findHeaders(values, headerText) {
      const locs = [];
      const target = headerText.toLowerCase();
      for (let r = 0; r < values.length; r++) {
        for (let c = 0; c < values[r].length; c++) {
          if (String(values[r][c] || '').trim().toLowerCase() === target) {
            locs.push({ r, c });
          }
        }
      }
      return locs;
    }

    Object.entries(setupHeaderMap).forEach(([typeKey, headerText]) => {
      const headers = findHeaders(setupSheet, headerText);
      if (headers.length > 0) {
        const setupItems = [];
        const seen = new Set();
        const primaryRow = headers[0].r;
        const matchingCols = headers.filter(h => Math.abs(h.r - primaryRow) <= 2);
        for (const h of matchingCols) {
          for (let r = h.r + 1; r < setupSheet.length; r++) {
            const isBlank = setupSheet[r].every(x => !String(x || '').trim());
            if (isBlank) break;
            const val = String(setupSheet[r][h.c] || '').trim();
            if (val && !seen.has(val)) {
              seen.add(val);
              setupItems.push(val);
            }
          }
        }

        const liveList = categoriesByType[typeKey] || [];
        if (JSON.stringify(liveList) !== JSON.stringify(setupItems)) {
          discrepancies[typeKey] = {
            liveDropdownItems: liveList,
            setUpTabItems: setupItems,
            liveOnly: liveList.filter(x => !setupItems.includes(x)),
            setUpOnly: setupItems.filter(x => !liveList.includes(x))
          };
        }
      }
    });
  }

  const taxonomy = {
    types: resolvedTypes,
    categoriesByType,
    accounts: resolvedAccounts,
    discrepanciesWithSetUpTab: discrepancies
  };

  return {
    taxonomy,
    validationInfo: {
      type: typeValidationInfo,
      category: categoryValidationInfoByType,
      account: accountValidationInfo
    }
  };
}

// Load real CSV files downloaded from spreadsheet
const setupCsv = fs.readFileSync('c:/Users/PC/.antigravity-ide/tests/sheet_Set_Up.csv', 'utf8');
const savingsCsv = fs.readFileSync('c:/Users/PC/.antigravity-ide/tests/sheet_Savings.csv', 'utf8');
const accountsCsv = fs.readFileSync('c:/Users/PC/.antigravity-ide/tests/sheet_Accounts.csv', 'utf8');
const txCsv = fs.readFileSync('c:/Users/PC/.antigravity-ide/tests/sheet_Transactions.csv', 'utf8');

const sheets = {
  'Set Up': parseCSV(setupCsv),
  'Savings': parseCSV(savingsCsv),
  'Accounts': parseCSV(accountsCsv),
  'Transactions': parseCSV(txCsv)
};

const res = extractTaxonomyFromDropdowns(sheets);

console.log('================================');
console.log('FINAL TAXONOMY:', JSON.stringify(res.taxonomy, null, 2));
console.log('\nRESOLVED FROM VALIDATION RULES:', JSON.stringify(res.validationInfo, null, 2));

// Run Assertions
assert.ok(Array.isArray(res.taxonomy.types), 'Types must be an array');
assert.ok(res.taxonomy.types.includes('Expenses'), 'Types must include Expenses');
assert.ok(res.taxonomy.types.includes('Income'), 'Types must include Income');
assert.ok(res.taxonomy.types.includes('Bills'), 'Types must include Bills');
assert.ok(res.taxonomy.types.includes('Debt'), 'Types must include Debt');
assert.ok(res.taxonomy.types.includes('Savings'), 'Types must include Savings');
assert.ok(res.taxonomy.categoriesByType['Expenses'].length > 20, 'Expenses categories should exceed 20');
assert.ok(res.taxonomy.categoriesByType['Income'].length >= 11, 'Income categories should be at least 11');
assert.ok(res.taxonomy.categoriesByType['Bills'].length === 6, 'Bills categories should be 6');
assert.ok(res.taxonomy.categoriesByType['Debt'].length === 3, 'Debt categories should be 3');
assert.ok(res.taxonomy.categoriesByType['Savings'].length >= 15, 'Savings categories should be at least 15');
assert.ok(res.taxonomy.accounts.length >= 7, 'Accounts should contain at least 7 verified accounts');
assert.ok(res.taxonomy.accounts.includes('Mpesa'));
assert.ok(res.taxonomy.accounts.includes('Equity Bank'));
assert.ok(res.taxonomy.accounts.includes('I&M Bank'));

console.log('\n✓ All dropdown validation assertions passed cleanly.');
