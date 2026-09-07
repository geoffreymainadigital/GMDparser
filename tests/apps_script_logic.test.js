/**
 * Automated Verification for Google Apps Script Validation Engine & Contracts
 */
import assert from 'node:assert';

const VALID_TYPES = ['Income', 'Expenses', 'Bills', 'Debt', 'Savings'];

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

  if (!tx.category || typeof tx.category !== 'string' || tx.category.trim() === '') {
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


function checkDuplicateTransactionCode(codesList, txCode) {
  if (!txCode) return { isDuplicate: false };
  const target = txCode.trim().toUpperCase();
  for (let i = 0; i < codesList.length; i++) {
    if (codesList[i] && String(codesList[i]).trim().toUpperCase() === target) {
      return { isDuplicate: true, row: i + 2 }; // +2 for header offset
    }
  }
  return { isDuplicate: false };
}

// Run Test Suite
console.log('Running Apps Script validation unit tests...');

// 1. Valid Expense
const validTx = {
  date: '2026-09-07',
  type: 'Expenses',
  category: 'Groceries',
  description: 'Naivas Supermarket',
  amount: 2540.00,
  account: 'M-PESA',
  transactionCode: 'TD47XYZ123'
};
const res1 = validateTransactionPayload(validTx);
assert.strictEqual(res1.isValid, true, 'Valid transaction should pass');
console.log('✓ Valid transaction accepted');

// 2. Reject zero or negative amount
const negativeTx = { ...validTx, amount: -50 };
const res2 = validateTransactionPayload(negativeTx);
assert.strictEqual(res2.isValid, false);
assert.ok(res2.errors[0].includes('positive non-zero'));
console.log('✓ Negative amount rejected');

// 3. Reject invalid type
const invalidTypeTx = { ...validTx, type: 'Miscellaneous' };
const res3 = validateTransactionPayload(invalidTypeTx);
assert.strictEqual(res3.isValid, false);
assert.ok(res3.errors[0].includes('Type must be one of'));
console.log('✓ Invalid type rejected');

// 4. Reject removed 'Transfer' type (only Income, Expenses, Bills, Debt, Savings allowed)
const transferTx = {
  date: '2026-09-07',
  type: 'Transfer',
  category: 'Internal Account Transfer',
  description: 'Transfer to NCBA',
  amount: 5000,
  account: 'Mpesa',
  transactionCode: 'TD47XYZ999'
};
const res4 = validateTransactionPayload(transferTx);
assert.strictEqual(res4.isValid, false);
assert.ok(res4.errors.some(e => e.includes('Type must be one of')));
console.log('✓ Invalid type "Transfer" rejected per real spreadsheet taxonomy');


// 5. Duplicate code check
const existingCodes = ['TD47XYZ123', 'TD47XYZ124', 'TD47XYZ125'];
const dupCheck = checkDuplicateTransactionCode(existingCodes, 'TD47XYZ123');
assert.strictEqual(dupCheck.isDuplicate, true);
assert.strictEqual(dupCheck.row, 2);
console.log('✓ Duplicate code detected accurately');

const nonDupCheck = checkDuplicateTransactionCode(existingCodes, 'TD99NEW000');
assert.strictEqual(nonDupCheck.isDuplicate, false);
console.log('✓ Non-duplicate code passed');

console.log('\nAll Apps Script validation unit tests PASSED successfully.');
