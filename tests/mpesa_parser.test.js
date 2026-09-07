/**
 * Automated Verification for Kenyan M-PESA SMS Parser
 */
import assert from 'node:assert';
import { MpesaParser } from './mpesa_parser.js';

console.log('Running M-PESA SMS parser unit tests...\n');

// 1. Paybill (KPLC Electricity)
const kplcMessage = 'TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00.';
const r1 = MpesaParser.parse(kplcMessage);
assert.strictEqual(r1.isFinancial, true);
assert.strictEqual(r1.transactionCode, 'TD47XYZ123');
assert.strictEqual(r1.amount, 3500.00);
assert.strictEqual(r1.balance, 12450.00);
assert.strictEqual(r1.cost, 23.00);
assert.strictEqual(r1.type, 'Bills');
assert.strictEqual(r1.category, 'Electricity / KPLC');
assert.strictEqual(r1.accountRef, '12345678');
assert.strictEqual(r1.date, '2026-09-07');
assert.strictEqual(r1.time, '8:15 PM');
console.log('✓ TC-SMS-01 (Paybill / KPLC) passed');

// 2. Buy Goods / Till (Naivas Supermarket)
const tillMessage = 'TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00.';
const r2 = MpesaParser.parse(tillMessage);
assert.strictEqual(r2.isFinancial, true);
assert.strictEqual(r2.transactionCode, 'TD48ABC456');
assert.strictEqual(r2.amount, 1250.00);
assert.strictEqual(r2.type, 'Expenses');
assert.strictEqual(r2.category, 'Groceries');
assert.strictEqual(r2.recipient, 'NAIVAS SUPERMARKET');
console.log('✓ TC-SMS-02 (Buy Goods / Till) passed');

// 3. P2P Send Money (Person to Person)
const p2pMessage = 'TD49DEF789 Confirmed. Ksh2,000.00 sent to JANE DOE 0712345678 on 7/9/26 at 10:05 AM. New M-PESA balance is Ksh9,200.00. Transaction cost, Ksh15.00.';
const r3 = MpesaParser.parse(p2pMessage);
assert.strictEqual(r3.isFinancial, true);
assert.strictEqual(r3.transactionCode, 'TD49DEF789');
assert.strictEqual(r3.amount, 2000.00);
assert.strictEqual(r3.recipient, 'JANE DOE');
assert.strictEqual(r3.phone, '0712345678');
assert.strictEqual(r3.type, 'Expenses');
console.log('✓ TC-SMS-03 (P2P Send Money) passed');

// 4. Received Money (Income)
const receivedMessage = 'TD50GHI012 Confirmed. You have received Ksh50,000.00 from ACME TECH LTD 0798765432 on 7/9/26 at 9:00 AM. New M-PESA balance is Ksh59,200.00.';
const r4 = MpesaParser.parse(receivedMessage);
assert.strictEqual(r4.isFinancial, true);
assert.strictEqual(r4.transactionCode, 'TD50GHI012');
assert.strictEqual(r4.amount, 50000.00);
assert.strictEqual(r4.sender, 'ACME TECH LTD');
assert.strictEqual(r4.type, 'Income');
console.log('✓ TC-SMS-04 (Received Money / Income) passed');

// 5. Bank Transfer (NCBA Loop)
const transferMessage = 'TD51JKL345 Confirmed. Ksh10,000.00 sent to NCBA LOOP for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh49,200.00.';
const r5 = MpesaParser.parse(transferMessage);
assert.strictEqual(r5.isFinancial, true);
assert.strictEqual(r5.transactionCode, 'TD51JKL345');
assert.strictEqual(r5.amount, 10000.00);
assert.strictEqual(r5.type, 'Transfer');
assert.strictEqual(r5.destinationAccount, 'Bank (NCBA Loop)');
console.log('✓ TC-SMS-05 (Bank Transfer) passed');

// 6. Savings / MMF Deposit
const mmfMessage = 'TD52MMF999 Confirmed. Ksh15,000.00 sent to CIC MONEY MARKET FUND for account 987654 on 7/9/26 at 11:00 AM. New M-PESA balance is Ksh34,200.00.';
const r6 = MpesaParser.parse(mmfMessage);
assert.strictEqual(r6.isFinancial, true);
assert.strictEqual(r6.transactionCode, 'TD52MMF999');
assert.strictEqual(r6.type, 'Savings');
assert.strictEqual(r6.category, 'Money Market Fund (MMF)');
console.log('✓ TC-SMS-06 (Savings / MMF) passed');

// 7. Cash Withdrawal from Agent
const withdrawMessage = 'TD53WTH888 Confirmed. on 7/9/26 at 4:45 PM Withdraw Ksh3,000.00 from 12345 - AGENT STORE New M-PESA balance is Ksh31,200.00. Transaction cost, Ksh28.00.';
const r7 = MpesaParser.parse(withdrawMessage);
assert.strictEqual(r7.isFinancial, true);
assert.strictEqual(r7.transactionCode, 'TD53WTH888');
assert.strictEqual(r7.amount, 3000.00);
assert.strictEqual(r7.type, 'Transfer');
assert.strictEqual(r7.destinationAccount, 'Cash');
console.log('✓ TC-SMS-07 (Agent Withdrawal to Cash) passed');

// 8. Non-Financial / Promotional SMS
const promoMessage = 'Dear Customer, get 500MB for Ksh50 valid for 24hrs. Dial *544# now.';
const r8 = MpesaParser.parse(promoMessage);
assert.strictEqual(r8.isFinancial, false);
console.log('✓ TC-SMS-08 (Promotional SMS Ignored) passed');

console.log('\nAll Kenyan M-PESA parser unit tests PASSED successfully!');
