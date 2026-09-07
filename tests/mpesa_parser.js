/**
 * Kenyan M-PESA SMS Deterministic Parser
 * High precision extractor for M-PESA transactions
 */

export class MpesaParser {
  /**
   * Parse an incoming SMS message text
   * @param {string} smsBody
   * @returns {object|null} Parsed transaction or null if not an M-PESA message
   */
  static parse(smsBody) {
    if (!smsBody || typeof smsBody !== 'string') {
      return null;
    }

    const trimmed = smsBody.trim();

    // Must contain Confirmed and an 8-12 char alphanumeric transaction code at start
    const codeMatch = trimmed.match(/^([A-Z0-9]{8,12})\s+Confirmed\./i);
    if (!codeMatch) {
      return {
        isFinancial: false,
        confidence: 0,
        rawText: trimmed,
        error: 'Not a standard confirmed M-PESA financial transaction'
      };
    }

    const transactionCode = codeMatch[1].toUpperCase();

    // 1. Extract Balance if present
    let balance = null;
    const balanceMatch = trimmed.match(/New M-PESA balance is Ksh([\d,]+\.?\d*)/i);
    if (balanceMatch) {
      balance = parseFloat(balanceMatch[1].replace(/,/g, ''));
    }

    // 2. Extract Transaction Cost if present
    let cost = null;
    const costMatch = trimmed.match(/Transaction cost,?\s*Ksh([\d,]+\.?\d*)/i);
    if (costMatch) {
      cost = parseFloat(costMatch[1].replace(/,/g, ''));
    }

    // 3. Pattern: Sent to Paybill / Buy Goods / Person
    // e.g. "Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM."
    // e.g. "Ksh2,000.00 sent to JANE DOE 0712345678 on 7/9/26 at 10:05 AM."
    const sentMatch = trimmed.match(/Ksh([\d,]+\.?\d*)\s+sent to\s+(.+?)\s+on\s+(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))/i);
    if (sentMatch) {
      const amount = parseFloat(sentMatch[1].replace(/,/g, ''));
      let targetRaw = sentMatch[2].trim();
      const dateStr = sentMatch[3];
      const timeStr = sentMatch[4];

      let accountRef = null;
      const acctMatch = targetRaw.match(/^(.+?)\s+for account\s+(.+)$/i);
      if (acctMatch) {
        targetRaw = acctMatch[1].trim();
        accountRef = acctMatch[2].trim();
      }

      let phone = null;
      const phoneMatch = targetRaw.match(/^(.+?)\s+(07\d{8}|01\d{8}|\+?254\d{9})$/);
      if (phoneMatch) {
        targetRaw = phoneMatch[1].trim();
        phone = phoneMatch[2].trim();
      }

      const classification = classifySentTransaction(targetRaw, accountRef);

      return {
        isFinancial: true,
        confidence: 0.95,
        transactionCode,
        amount,
        balance,
        cost,
        date: formatDateIso(dateStr),
        rawDate: dateStr,
        time: timeStr,
        recipient: targetRaw,
        description: targetRaw + (accountRef ? ` (${accountRef})` : ''),
        accountRef,
        phone,
        type: classification.type,
        category: classification.category,
        account: 'Mpesa',
        destinationAccount: classification.destinationAccount || null,
        rawText: trimmed
      };
    }

    // 4. Pattern: Paid to Till / Buy Goods
    // e.g. "Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM."
    const paidMatch = trimmed.match(/Ksh([\d,]+\.?\d*)\s+paid to\s+(.+?)(?:\.|\s+on)\s+(?:on\s+)?(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))/i);
    if (paidMatch) {
      const amount = parseFloat(paidMatch[1].replace(/,/g, ''));
      let merchant = paidMatch[2].trim().replace(/\.$/, '');
      const dateStr = paidMatch[3];
      const timeStr = paidMatch[4];

      const classification = classifyPaidMerchant(merchant);

      return {
        isFinancial: true,
        confidence: 0.95,
        transactionCode,
        amount,
        balance,
        cost,
        date: formatDateIso(dateStr),
        rawDate: dateStr,
        time: timeStr,
        recipient: merchant,
        description: merchant,
        type: classification.type,
        category: classification.category,
        account: 'Mpesa',
        destinationAccount: null,
        rawText: trimmed
      };
    }

    // 5. Pattern: Received Money
    // e.g. "You have received Ksh50,000.00 from ACME TECH LTD 0798765432 on 7/9/26 at 9:00 AM."
    const receivedMatch = trimmed.match(/You have received Ksh([\d,]+\.?\d*)\s+from\s+(.+?)\s+on\s+(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))/i);
    if (receivedMatch) {
      const amount = parseFloat(receivedMatch[1].replace(/,/g, ''));
      let senderRaw = receivedMatch[2].trim();
      const dateStr = receivedMatch[3];
      const timeStr = receivedMatch[4];

      let phone = null;
      const phoneMatch = senderRaw.match(/^(.+?)\s+(07\d{8}|01\d{8}|\+?254\d{9})$/);
      if (phoneMatch) {
        senderRaw = phoneMatch[1].trim();
        phone = phoneMatch[2].trim();
      }

      return {
        isFinancial: true,
        confidence: 0.95,
        transactionCode,
        amount,
        balance,
        cost: 0,
        date: formatDateIso(dateStr),
        rawDate: dateStr,
        time: timeStr,
        sender: senderRaw,
        description: `Received from ${senderRaw}`,
        phone,
        type: 'Income',
        category: 'Salary', // Default suggestion, reviewed by user
        account: 'Mpesa',
        destinationAccount: null,
        rawText: trimmed
      };
    }

    // 6. Pattern: Cash Withdrawal from Agent
    // e.g. "on 7/9/26 at 4:45 PM Withdraw Ksh3,000.00 from 12345 - AGENT STORE"
    const withdrawMatch = trimmed.match(/on\s+(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))\s+Withdraw\s+Ksh([\d,]+\.?\d*)\s+from\s+(.+?)(?:\s+New|\.|$)/i);
    if (withdrawMatch) {
      const dateStr = withdrawMatch[1];
      const timeStr = withdrawMatch[2];
      const amount = parseFloat(withdrawMatch[3].replace(/,/g, ''));
      const agent = withdrawMatch[4].trim();

      return {
        isFinancial: true,
        confidence: 0.90,
        transactionCode,
        amount,
        balance,
        cost,
        date: formatDateIso(dateStr),
        rawDate: dateStr,
        time: timeStr,
        recipient: agent,
        description: `Withdrawal: ${agent}`,
        type: 'Transfer',
        category: 'Internal Account Transfer',
        account: 'Mpesa',
        destinationAccount: 'Cash',
        rawText: trimmed
      };
    }

    // Unmatched pattern despite Confirmed code -> Flag for manual review
    return {
      isFinancial: true,
      confidence: 0.40,
      transactionCode,
      amount: null,
      balance,
      date: new Date().toISOString().split('T')[0],
      type: 'Expenses',
      category: 'Uncategorized',
      account: 'Mpesa',
      destinationAccount: null,
      rawText: trimmed,
      note: 'Ambiguous SMS pattern: manual input required'
    };
  }
}

/**
 * Classifies paybill / send money targets into categories & types
 */
function classifySentTransaction(recipient, accountRef) {
  const r = (recipient || '').toLowerCase();
  const a = (accountRef || '').toLowerCase();

  // 1. Debt & Loans (check loan keywords first to avoid Sacco vs Sacco Loan collision)
  if (r.includes('equity loan') || (r.includes('equity') && (r.includes('loan') || a.includes('loan')))) {
    return { type: 'Debt', category: 'Equity Loan' };
  }
  if (r.includes('nca sacco loan') || (r.includes('nca') && (r.includes('loan') || a.includes('loan')))) {
    return { type: 'Debt', category: 'NCA Sacco Loan' };
  }
  if (r.includes('helb')) {
    return { type: 'Debt', category: 'Helb Loan' };
  }
  if (r.includes('fuliza') || r.includes('m-shwari') || r.includes('tala') || r.includes('branch')) {
    return { type: 'Debt', category: 'Equity Loan' };
  }

  // 2. Specific Savings Institutions
  // Tower Sacco: Payment to Tower Sacco via SMS is a Savings contribution funded from Mpesa
  if (r.includes('tower sacco') || r.includes('tower')) {
    return { type: 'Savings', category: 'Tower Sacco', destinationAccount: null };
  }
  // NCA Sacco: Payment to NCA Sacco (without loan) is a Savings contribution
  if (r.includes('nca sacco') || r.includes('nca')) {
    return { type: 'Savings', category: 'NCA Sacco', destinationAccount: null };
  }
  if (r.includes('sanlam')) {
    return { type: 'Savings', category: 'Sanlam MMF', destinationAccount: null };
  }
  if (r.includes('britam')) {
    return { type: 'Savings', category: 'Britam EQ and MMF', destinationAccount: null };
  }
  if (r.includes('etica')) {
    return { type: 'Savings', category: 'Etica MMF', destinationAccount: null };
  }
  if (r.includes('ziidi')) {
    return { type: 'Savings', category: 'Ziidi MMF', destinationAccount: null };
  }
  if (r.includes('cic') || r.includes('mmf') || r.includes('money market')) {
    return { type: 'Savings', category: 'Money Market Fund (MMF)', destinationAccount: 'MMF Account' };
  }

  // 3. Matatu Transport Saccos (Expenses -> Fare)
  if (r.includes('super metro') || r.includes('2nk') || r.includes('lopha') || r.includes('kbs') || r.includes('city hoppa') || r.includes('metro') || r.includes('matatu')) {
    return { type: 'Expenses', category: 'Fare' };
  }

  // 4. Utilities / Bills
  if (r.includes('kenya power') || r.includes('kplc')) {
    return { type: 'Bills', category: 'Electricity / KPLC' };
  }
  if (r.includes('nairobi water') || r.includes('water')) {
    return { type: 'Bills', category: 'Water' };
  }
  if (r.includes('safaricom home') || r.includes('zuku') || r.includes('faiba') || r.includes('poa')) {
    return { type: 'Bills', category: 'Internet / WiFi' };
  }

  // 5. Bank Transfers
  if (r.includes('ncba loop') || r.includes('loop')) {
    return { type: 'Transfer', category: 'Internal Account Transfer', destinationAccount: 'Bank (NCBA Loop)' };
  }
  if (r.includes('equity') || r.includes('equity bank')) {
    return { type: 'Transfer', category: 'Internal Account Transfer', destinationAccount: 'Bank (Equity)' };
  }
  if (r.includes('kcb') || r.includes('kcb bank')) {
    return { type: 'Transfer', category: 'Internal Account Transfer', destinationAccount: 'Bank (KCB)' };
  }
  if (r.includes('sacco') || r.includes('stima')) {
    return { type: 'Transfer', category: 'Internal Account Transfer', destinationAccount: 'SACCO Account' };
  }

  // Default P2P
  return { type: 'Expenses', category: 'Gifts / Support' };
}

/**
 * Classifies Buy Goods merchant names
 */
function classifyPaidMerchant(merchant) {
  const m = (merchant || '').toLowerCase();

  // Groceries / Supermarket
  if (m.includes('naivas') || m.includes('carrefour') || m.includes('quickmart') || m.includes('chandarana') || m.includes('cleanshelf')) {
    return { type: 'Expenses', category: 'Groceries' };
  }

  // Dining
  if (m.includes('kfc') || m.includes('java') || m.includes('artcaffe') || m.includes('pizza') || m.includes('restaurant') || m.includes('cafe')) {
    return { type: 'Expenses', category: 'Dining Out / Takeout' };
  }

  // Fuel / Transport
  if (m.includes('total') || m.includes('shell') || m.includes('rubis') || m.includes('ola') || m.includes('uber') || m.includes('bolt')) {
    return { type: 'Expenses', category: 'Transport & Fuel' };
  }

  // Health
  if (m.includes('pharmacy') || m.includes('chemist') || m.includes('hospital') || m.includes('clinic')) {
    return { type: 'Expenses', category: 'Health & Pharmacy' };
  }

  return { type: 'Expenses', category: 'Shopping & Clothing' };
}

/**
 * Formats "D/M/YY" or "DD/MM/YYYY" to "YYYY-MM-DD"
 */
function formatDateIso(dateStr) {
  if (!dateStr) return new Date().toISOString().split('T')[0];
  const parts = dateStr.split('/');
  if (parts.length !== 3) return dateStr;

  let day = parts[0].padStart(2, '0');
  let month = parts[1].padStart(2, '0');
  let year = parts[2];
  if (year.length === 2) {
    year = '20' + year;
  }
  return `${year}-${month}-${day}`;
}
