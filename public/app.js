/**
 * GMDParser Web Application Engine
 * Responsive financial operating system client
 */

// Category taxonomy by transaction type (Synchronized with Spreadsheet Set Up, Savings, and Accounts tabs)
const TAXONOMY = {
  'Income': [
    'Salary',
    'Livestream/Photo/Video',
    'Camera Income',
    'Money Diary Kenya',
    'Return On Investments',
    'Random Money',
    'Paradise Point Resort Tokens',
    'Loans',
    'Work Allowances',
    'Wifi Clients',
    'Work Airtime'
  ],
  'Bills': [
    'Rent',
    'Monthly Shopping',
    'WIFI',
    'Minutes',
    'Electricity',
    'Water'
  ],
  'Debt': [
    'Equity Loan',
    'NCA Sacco Loan',
    'Helb Loan'
  ],
  'Expenses': [
    'Mama Mboga',
    'Eating Out',
    'Work costs',
    'Side Hustle Costs',
    'Bundles',
    'Transaction Cost',
    'Kinyozi',
    'Mama Fua',
    'Fare',
    'MDK',
    'Boda',
    'Wife Allowances',
    'Black Tax',
    'Car Hire',
    'Shoes',
    'Clothes',
    'Suits',
    'Electronics',
    'Kitchenwares',
    'Marriage Process',
    'Gas',
    'House Supplies',
    'Water Refilling',
    'Donations'
  ],
  'Savings': [
    'Sanlam MMF',
    'Britam EQ and MMF',
    'Etica MMF',
    'AIB Stocks',
    'SOL',
    'USDT',
    'BTC',
    'Other Crypto Coins',
    'NCA Sacco',
    'Tower Sacco',
    'Ziidi MMF',
    'Faida Stocks',
    'Ziidi Stocks',
    'ETH',
    'Arvocap'
  ],
  'Balance': []
};

const SAMPLE_MESSAGES = {
  kplc: 'TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00.',
  mamamboga: 'TD48ABC456 Confirmed. Ksh450.00 paid to MAMA MBOGA GROCERIES. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00.',
  fare: 'TD49DEF789 Confirmed. Ksh300.00 sent to JOHN BODA 0712345678 on 7/9/26 at 10:05 AM. New M-PESA balance is Ksh9,200.00. Transaction cost, Ksh0.00.',
  income: 'TD50GHI012 Confirmed. You have received Ksh85,000.00 from EMPLOYER LTD 0798765432 on 7/9/26 at 9:00 AM. New M-PESA balance is Ksh94,200.00.',
  transfer: 'TD51JKL345 Confirmed. Ksh10,000.00 sent to EQUITY BANK for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh84,200.00.'
};

// Application State
const state = {
  pendingReviews: [],
  confirmedRecords: [],
  accounts: [
    { name: 'Mpesa', type: 'Mobile Money', balance: 0.00 },
    { name: 'Equity Bank', type: 'Bank Account', balance: 0.00 },
    { name: 'I&M Bank', type: 'Bank Account', balance: 0.00 },
    { name: 'Cash', type: 'Cash Wallet', balance: 0.00 },
    { name: 'Till Number', type: 'Merchant Till', balance: 0.00 },
    { name: 'Tower Sacco', type: 'SACCO Account', balance: 0.00 },
    { name: 'Airtime', type: 'Airtime Account', balance: 0.00 }
  ]
};

// Deterministic Client-Side M-PESA Parser (Supports 8-12 char codes)
function parseMpesaMessage(smsBody) {
  if (!smsBody || typeof smsBody !== 'string') return null;
  const trimmed = smsBody.trim();

  // Support 8-12 alphanumeric codes
  const codeMatch = trimmed.match(/^([A-Z0-9]{8,12})\s+Confirmed\./i);
  if (!codeMatch) return null;

  const transactionCode = codeMatch[1].toUpperCase();

  let balance = null;
  const balanceMatch = trimmed.match(/New M-PESA balance is Ksh([\d,]+\.?\d*)/i);
  if (balanceMatch) balance = parseFloat(balanceMatch[1].replace(/,/g, ''));

  let cost = null;
  const costMatch = trimmed.match(/Transaction cost,?\s*Ksh([\d,]+\.?\d*)/i);
  if (costMatch) cost = parseFloat(costMatch[1].replace(/,/g, ''));

  // 1. Sent to (Paybill / Send Money / Bank Transfer)
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

    const lower = targetRaw.toLowerCase();
    let type = 'Expenses';
    let category = 'Fare';
    let destAccount = null;

    // 1. Debt & Loan Repayments
    if (lower.includes('equity loan') || (lower.includes('equity') && (lower.includes('loan') || (accountRef && accountRef.toLowerCase().includes('loan'))))) {
      type = 'Debt'; category = 'Equity Loan';
    } else if (lower.includes('nca sacco loan') || (lower.includes('nca sacco') && (lower.includes('loan') || (accountRef && accountRef.toLowerCase().includes('loan'))))) {
      type = 'Debt'; category = 'NCA Sacco Loan';
    } else if (lower.includes('helb')) {
      type = 'Debt'; category = 'Helb Loan';
    // 2. Savings Contributions (Tower Sacco & NCA Sacco)
    } else if (lower.includes('tower sacco')) {
      type = 'Savings'; category = 'Tower Sacco'; destAccount = null;
    } else if (lower.includes('nca sacco')) {
      type = 'Savings'; category = 'NCA Sacco'; destAccount = null;
    } else if (lower.includes('sanlam')) {
      type = 'Savings'; category = 'Sanlam MMF'; destAccount = null;
    } else if (lower.includes('britam')) {
      type = 'Savings'; category = 'Britam EQ and MMF'; destAccount = null;
    } else if (lower.includes('etica')) {
      type = 'Savings'; category = 'Etica MMF'; destAccount = null;
    // 3. Matatu Transport Saccos
    } else if (lower.includes('super metro') || lower.includes('2nk') || lower.includes('lopha') || lower.includes('metro')) {
      type = 'Expenses'; category = 'Fare';
    // 4. Utilities & Bills
    } else if (lower.includes('kenya power') || lower.includes('kplc') || lower.includes('electricity')) {
      type = 'Bills'; category = 'Electricity';
    } else if (lower.includes('water')) {
      type = 'Bills'; category = 'Water';
    } else if (lower.includes('safaricom home') || lower.includes('zuku') || lower.includes('faiba') || lower.includes('wifi') || lower.includes('poa')) {
      type = 'Bills'; category = 'WIFI';
    // 5. Bank Transfers
    } else if (lower.includes('equity')) {
      type = 'Bills'; destAccount = 'Equity Bank'; category = 'Monthly Shopping';
    } else if (lower.includes('boda') || lower.includes('fare')) {
      type = 'Expenses'; category = 'Fare';
    }

    return {
      transactionCode,
      amount,
      balance,
      cost,
      date: formatIsoDate(dateStr),
      time: timeStr,
      recipient: targetRaw,
      description: targetRaw + (accountRef ? ` (${accountRef})` : ''),
      type,
      category,
      account: 'Mpesa',
      destinationAccount: destAccount,
      rawText: trimmed
    };
  }

  // 2. Paid to (Buy Goods / Till)
  const paidMatch = trimmed.match(/Ksh([\d,]+\.?\d*)\s+paid to\s+(.+?)(?:\.|\s+on)\s+(?:on\s+)?(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))/i);
  if (paidMatch) {
    const amount = parseFloat(paidMatch[1].replace(/,/g, ''));
    const merchant = paidMatch[2].trim().replace(/\.$/, '');
    const dateStr = paidMatch[3];
    const timeStr = paidMatch[4];

    const lower = merchant.toLowerCase();
    let category = 'House Supplies';
    let type = 'Expenses';
    if (lower.includes('mboga') || lower.includes('market') || lower.includes('fruit') || lower.includes('veg')) {
      category = 'Mama Mboga';
    } else if (lower.includes('supermarket') || lower.includes('naivas') || lower.includes('carrefour') || lower.includes('quickmart')) {
      type = 'Bills';
      category = 'Monthly Shopping';
    } else if (lower.includes('kfc') || lower.includes('java') || lower.includes('artcaffe') || lower.includes('restaurant') || lower.includes('hotel') || lower.includes('cafe')) {
      category = 'Eating Out';
    } else if (lower.includes('boda') || lower.includes('fare') || lower.includes('uber') || lower.includes('bolt') || lower.includes('matatu')) {
      category = 'Fare';
    }

    return {
      transactionCode,
      amount,
      balance,
      cost,
      date: formatIsoDate(dateStr),
      time: timeStr,
      recipient: merchant,
      description: merchant,
      type,
      category,
      account: 'Mpesa',
      destinationAccount: null,
      rawText: trimmed
    };
  }

  // 3. Received Money
  const receivedMatch = trimmed.match(/You have received Ksh([\d,]+\.?\d*)\s+from\s+(.+?)\s+on\s+(\d{1,2}\/\d{1,2}\/\d{2,4})\s+at\s+(\d{1,2}:\d{2}\s*(?:AM|PM))/i);
  if (receivedMatch) {
    const amount = parseFloat(receivedMatch[1].replace(/,/g, ''));
    const sender = receivedMatch[2].trim();
    const dateStr = receivedMatch[3];
    const timeStr = receivedMatch[4];

    const sLower = sender.toLowerCase();
    const isSavingsWithdrawal = sLower.includes('sanlam') ||
      sLower.includes('britam') ||
      sLower.includes('etica') ||
      sLower.includes('ziidi') ||
      sLower.includes('cic') ||
      sLower.includes('arvocap') ||
      sLower.includes('faida') ||
      sLower.includes('aib') ||
      sLower.includes('mmf');

    let finalType = 'Income';
    let finalCat = 'Salary';
    let finalAmt = amount;
    let finalDesc = `Received from ${sender}`;

    if (isSavingsWithdrawal) {
      finalType = 'Savings';
      finalAmt = -Math.abs(amount);
      finalDesc = `Withdrawal from ${sender}`;
      if (sLower.includes('sanlam')) finalCat = 'Sanlam MMF';
      else if (sLower.includes('britam')) finalCat = 'Britam EQ and MMF';
      else if (sLower.includes('etica')) finalCat = 'Etica MMF';
      else if (sLower.includes('ziidi')) finalCat = 'Ziidi MMF';
      else if (sLower.includes('faida')) finalCat = 'Faida Stocks';
      else if (sLower.includes('aib')) finalCat = 'AIB Stocks';
      else if (sLower.includes('arvocap')) finalCat = 'Arvocap';
      else finalCat = 'Sanlam MMF';
    }

    return {
      transactionCode,
      amount: finalAmt,
      balance,
      cost: 0,
      date: formatIsoDate(dateStr),
      time: timeStr,
      sender,
      description: finalDesc,
      type: finalType,
      category: finalCat,
      account: 'Mpesa',
      destinationAccount: null,
      rawText: trimmed
    };
  }

  return null;
}

function formatIsoDate(rawDate) {
  if (!rawDate) return new Date().toISOString().split('T')[0];
  const parts = rawDate.split('/');
  if (parts.length !== 3) return rawDate;
  const day = parts[0].padStart(2, '0');
  const month = parts[1].padStart(2, '0');
  let year = parts[2];
  if (year.length === 2) year = '20' + year;
  return `${year}-${month}-${day}`;
}

// Navigation & Tab Switching
function initNavigation() {
  const navItems = document.querySelectorAll('.nav-item');
  navItems.forEach(item => {
    item.addEventListener('click', (e) => {
      e.preventDefault();
      const tabId = item.getAttribute('data-tab');
      if (tabId) {
        window.location.hash = tabId;
        switchTab(tabId);
      }
    });
  });

  // Jump links
  document.querySelectorAll('.nav-jump').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const tabId = btn.getAttribute('data-tab');
      if (tabId) {
        window.location.hash = tabId;
        switchTab(tabId);
      }
    });
  });

  // Handle hash on direct URL load and hash changes
  window.addEventListener('hashchange', () => {
    const hash = window.location.hash.replace('#', '').trim();
    if (hash) {
      switchTab(hash);
    }
  });

  const initialHash = window.location.hash.replace('#', '').trim();
  if (initialHash) {
    switchTab(initialHash);
  }

  // Mobile menu toggle
  const mobileToggle = document.getElementById('mobile-menu-toggle');
  const sidebar = document.getElementById('sidebar');
  if (mobileToggle && sidebar) {
    mobileToggle.addEventListener('click', () => {
      sidebar.classList.toggle('open');
    });
  }
}

function switchTab(tabId) {
  document.querySelectorAll('.nav-item').forEach(el => {
    el.classList.toggle('active', el.getAttribute('data-tab') === tabId);
  });
  document.querySelectorAll('.tab-view').forEach(view => {
    view.classList.toggle('active', view.id === `view-${tabId}`);
  });

  const pageTitle = document.getElementById('page-title');
  const titles = {
    dashboard: 'Dashboard',
    review: 'Review & Confirm',
    history: 'Ledger History',
    accounts: 'Accounts & Transfers',
    diagnostics: 'System Diagnostics',
    settings: 'Settings'
  };
  if (pageTitle) pageTitle.textContent = titles[tabId] || 'GMDParser';

  const sidebar = document.getElementById('sidebar');
  if (sidebar) sidebar.classList.remove('open');

  if (tabId === 'diagnostics') {
    runSystemDiagnostics();
  }
}

// Toast Notifications
function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = message;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 200);
  }, 4000);
}

// Pending Review Cards Rendering
function renderReviewCards() {
  const container = document.getElementById('review-container');
  const badge = document.getElementById('pending-badge');
  const dashCount = document.getElementById('dash-pending-count');

  if (badge) {
    badge.textContent = state.pendingReviews.length;
    badge.classList.toggle('hidden', state.pendingReviews.length === 0);
  }
  if (dashCount) {
    dashCount.textContent = state.pendingReviews.length;
  }

  if (!container) return;

  if (state.pendingReviews.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-icon">✓</div>
        <h3>Review Queue is Empty</h3>
        <p>Paste an SMS on the Dashboard or trigger an M-PESA broadcast to stage transactions for review.</p>
      </div>
    `;
    return;
  }

  container.innerHTML = '';
  state.pendingReviews.forEach((tx, index) => {
    const card = document.createElement('div');
    card.className = 'review-item-card';

    const isTransfer = tx.type === 'Transfer' || tx.type === 'Balance';
    const categoriesForType = TAXONOMY[tx.type] || (tx.type === 'Balance' ? [''] : ['General']);

    card.innerHTML = `
      <div class="review-item-header">
        <div>
          <span class="tx-code-badge">${tx.transactionCode}</span>
          <span class="tag tag-green" style="margin-left: 8px;">Pending Review</span>
        </div>
        <span class="tx-time-text">${tx.date} ${tx.time || ''}</span>
      </div>

      <div class="form-grid">
        <div class="form-group">
          <label>Amount (KES)</label>
          <input type="number" step="0.01" value="${tx.amount}" id="tx-amount-${index}" />
        </div>
        <div class="form-group">
          <label>Transaction Type</label>
          <select id="tx-type-${index}">
            ${Object.keys(TAXONOMY).map(t =>
              `<option value="${t}" ${t === tx.type ? 'selected' : ''}>${t}</option>`
            ).join('')}
          </select>
        </div>
        <div class="form-group">
          <label>Category</label>
          <select id="tx-category-${index}">
            ${categoriesForType.map(c =>
              `<option value="${c}" ${c === tx.category ? 'selected' : ''}>${c}</option>`
            ).join('')}
          </select>
        </div>
        <div class="form-group">
          <label>Payee / Description</label>
          <input type="text" value="${tx.description}" id="tx-desc-${index}" />
        </div>
        <div class="form-group">
          <label>Funding Account</label>
          <select id="tx-account-${index}">
            ${state.accounts.map(a => `<option value="${a.name}" ${a.name === tx.account ? 'selected' : ''}>${a.name}</option>`).join('')}
          </select>
        </div>
        <div class="form-group" id="tx-dest-group-${index}" style="${isTransfer ? '' : 'display: none;'}">
          <label style="color: var(--color-cyan);">Destination Account (Transfer Only)</label>
          <select id="tx-dest-${index}">
            ${state.accounts.map(a => `<option value="${a.name}" ${a.name === tx.destinationAccount ? 'selected' : ''}>${a.name}</option>`).join('')}
          </select>
        </div>
      </div>

      ${tx.rawText ? `<div class="review-raw-sms">${tx.rawText}</div>` : ''}

      <div class="review-actions-row">
        <button class="btn btn-danger btn-sm btn-dismiss" data-index="${index}">Dismiss</button>
        <button class="btn btn-primary btn-confirm" data-index="${index}" id="btn-confirm-${index}">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
          Confirm & Record to Sheet
        </button>
      </div>
    `;

    container.appendChild(card);

    // Dynamic type change updates categories & transfer destination visibility
    const typeSelect = card.querySelector(`#tx-type-${index}`);
    const catSelect = card.querySelector(`#tx-category-${index}`);
    const destGroup = card.querySelector(`#tx-dest-group-${index}`);

    typeSelect.addEventListener('change', (e) => {
      const selectedType = e.target.value;
      const cats = TAXONOMY[selectedType] || (selectedType === 'Balance' ? [''] : ['General']);
      catSelect.innerHTML = cats.map(c => `<option value="${c}">${c}</option>`).join('');
      destGroup.style.display = (selectedType === 'Transfer' || selectedType === 'Balance') ? 'block' : 'none';
    });

    // Dismiss Handler
    card.querySelector('.btn-dismiss').addEventListener('click', () => {
      state.pendingReviews.splice(index, 1);
      renderReviewCards();
      showToast(`Dismissed transaction ${tx.transactionCode}`, 'info');
    });

    // Confirm Handler
    card.querySelector('.btn-confirm').addEventListener('click', async () => {
      const confirmBtn = card.querySelector(`#btn-confirm-${index}`);
      confirmBtn.disabled = true;
      confirmBtn.innerHTML = 'Submitting to Sheet...';

      const confirmedTx = {
        date: tx.date,
        type: typeSelect.value,
        category: catSelect.value,
        description: card.querySelector(`#tx-desc-${index}`).value.trim(),
        amount: parseFloat(card.querySelector(`#tx-amount-${index}`).value) || tx.amount,
        account: card.querySelector(`#tx-account-${index}`).value.trim(),
        transactionCode: tx.transactionCode,
        destinationAccount: typeSelect.value === 'Transfer' ? card.querySelector(`#tx-dest-${index}`).value.trim() : null
      };

      try {
        const response = await fetch('/api/transaction', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            action: 'createTransaction',
            transaction: confirmedTx
          })
        });

        const result = await response.json();

        if (response.status === 201 || result.success) {
          showToast(`✓ Recorded ${confirmedTx.transactionCode} to row ${result.data?.row || 'next available'}`, 'success');
          state.confirmedRecords.unshift({
            ...confirmedTx,
            status: 'SYNCED',
            row: result.data?.row || null,
            timestamp: new Date().toISOString()
          });
          state.pendingReviews.splice(index, 1);
          renderReviewCards();
          renderLedgerTables();
          updateDashboardMetrics();
        } else if (response.status === 409 || result.status === 'DUPLICATE_TRANSACTION_CODE') {
          showToast(`⚠️ Duplicate Code: ${result.error || 'Transaction already exists in sheet'}`, 'error');
          state.confirmedRecords.unshift({
            ...confirmedTx,
            status: 'DUPLICATE',
            timestamp: new Date().toISOString()
          });
          state.pendingReviews.splice(index, 1);
          renderReviewCards();
          renderLedgerTables();
        } else {
          showToast(`Error: ${result.error || 'Failed writing to sheet'}`, 'error');
          confirmBtn.disabled = false;
          confirmBtn.innerHTML = 'Retry Confirm & Record';
        }
      } catch (err) {
        showToast(`Network Error: ${err.message}`, 'error');
        confirmBtn.disabled = false;
        confirmBtn.innerHTML = 'Retry Confirm & Record';
      }
    });
  });
}

// Ledger Table Rendering
function renderLedgerTables() {
  const dashTbody = document.getElementById('dashboard-recent-tbody');
  const histTbody = document.getElementById('history-tbody');

  const rowsHtml = state.confirmedRecords.length === 0
    ? `<tr><td colspan="8" class="text-center text-muted">No confirmed transactions logged yet.</td></tr>`
    : state.confirmedRecords.map(r => `
      <tr>
        <td>${r.date}</td>
        <td><code>${r.transactionCode}</code></td>
        <td><span class="tag tag-cyan">${r.type}</span></td>
        <td>${r.category}</td>
        <td>${r.description}</td>
        <td>${r.account}</td>
        <td><strong>Ksh ${Number(r.amount).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</strong></td>
        <td>
          <span class="tag ${r.status === 'SYNCED' ? 'tag-green' : 'tag-amber'}">${r.status}</span>
        </td>
      </tr>
    `).join('');

  if (dashTbody) dashTbody.innerHTML = rowsHtml;
  if (histTbody) histTbody.innerHTML = rowsHtml;
}

function updateDashboardMetrics() {
  const inflow = state.confirmedRecords
    .filter(r => r.type === 'Income' && r.status === 'SYNCED')
    .reduce((acc, c) => acc + (Number(c.amount) || 0), 0);

  const expenses = state.confirmedRecords
    .filter(r => (r.type === 'Expenses' || r.type === 'Bills') && r.status === 'SYNCED')
    .reduce((acc, c) => acc + (Number(c.amount) || 0), 0);

  const transfers = state.confirmedRecords
    .filter(r => r.type === 'Transfer' && r.status === 'SYNCED')
    .reduce((acc, c) => acc + (Number(c.amount) || 0), 0);

  const elInflow = document.getElementById('dash-inflow');
  const elExp = document.getElementById('dash-expenses');
  const elTrans = document.getElementById('dash-transfers');

  if (elInflow) elInflow.textContent = `Ksh ${inflow.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
  if (elExp) elExp.textContent = `Ksh ${expenses.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
  if (elTrans) elTrans.textContent = `Ksh ${transfers.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
}

// Accounts Initializer & Synchronizer
function renderAccountsAndBudgets() {
  const accountsGrid = document.getElementById('accounts-grid');
  if (accountsGrid) {
    accountsGrid.innerHTML = state.accounts.map(a => {
      let balance = a.balance || 0;
      if (Array.isArray(state.accountsData)) {
        const liveAcc = state.accountsData.find(la => la.accountName.toLowerCase() === a.name.toLowerCase());
        if (liveAcc && liveAcc.currentBalance !== undefined) {
          balance = liveAcc.currentBalance;
        }
      }
      return `
        <div class="account-item-card">
          <div>
            <div class="acc-title">${a.name}</div>
            <div class="acc-type">${a.type}</div>
          </div>
          <div class="acc-bal">Ksh ${Number(balance).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</div>
        </div>
      `;
    }).join('');
  }
}

// System Diagnostics Check
async function runSystemDiagnostics() {
  const badgeVercel = document.getElementById('badge-vercel');
  const bodyVercel = document.getElementById('body-vercel');
  const badgeApi = document.getElementById('badge-api');
  const bodyApi = document.getElementById('body-api');
  const badgeGas = document.getElementById('badge-gas');
  const bodyGas = document.getElementById('body-gas');
  const badgeSheet = document.getElementById('badge-sheet');
  const bodySheet = document.getElementById('body-sheet');
  const rawLog = document.getElementById('diag-raw-output');

  if (rawLog) rawLog.textContent = 'Running diagnostics probe on /api/test-connection...\n';

  try {
    const res = await fetch('/api/test-connection');
    const data = await res.json();
    if (rawLog) rawLog.textContent = JSON.stringify(data, null, 2);

    // 1. Vercel
    if (data.vercel && data.vercel.status === 'online') {
      badgeVercel.textContent = 'HEALTHY';
      badgeVercel.className = 'diag-badge healthy';
      bodyVercel.textContent = `Production ingress active (${data.vercel.productionUrl}, region: ${data.vercel.region})`;
    } else {
      badgeVercel.textContent = 'ERROR';
      badgeVercel.className = 'diag-badge error';
    }

    // 2. API Gateway
    badgeApi.textContent = 'ONLINE';
    badgeApi.className = 'diag-badge healthy';
    bodyApi.textContent = `API gateway responding (Gateway Latency: ${data.vercel.gatewayLatencyMs || 5}ms)`;

    // 3. Google Apps Script
    if (data.appsScript.status === 'healthy') {
      badgeGas.textContent = 'CONNECTED';
      badgeGas.className = 'diag-badge healthy';
      bodyGas.textContent = `Upstream Apps Script responding (${data.appsScript.latencyMs}ms)`;
    } else if (data.appsScript.status === 'unconfigured') {
      badgeGas.textContent = 'PENDING CONFIG';
      badgeGas.className = 'diag-badge warning';
      bodyGas.textContent = 'APPS_SCRIPT_URL not yet configured in Vercel settings.';
    } else {
      badgeGas.textContent = 'UNREACHABLE';
      badgeGas.className = 'diag-badge error';
      bodyGas.textContent = `Upstream error: ${data.appsScript.error || data.appsScript.status}`;
    }

    // 4. Google Sheets
    if (data.googleSheets.status === 'connected') {
      badgeSheet.textContent = 'BOUND';
      badgeSheet.className = 'diag-badge healthy';
      bodySheet.textContent = `Transactions sheet bound & contract write boundaries C:D, G:H, J:L active.`;
    } else if (data.appsScript.status === 'unconfigured') {
      badgeSheet.textContent = 'WAITING';
      badgeSheet.className = 'diag-badge warning';
      bodySheet.textContent = 'Awaiting Apps Script deployment to verify sheet connection.';
    } else {
      badgeSheet.textContent = 'CHECK CONFIG';
      badgeSheet.className = 'diag-badge warning';
      bodySheet.textContent = `Sheet status: ${data.googleSheets.status}`;
    }

  } catch (err) {
    if (rawLog) rawLog.textContent += `\nError running test-connection: ${err.message}`;
    badgeApi.textContent = 'ERROR';
    badgeApi.className = 'diag-badge error';
  }
}

// Background Network Probe for Topbar Pill
async function checkNetworkStatus() {
  const pill = document.getElementById('conn-pill');
  const text = document.getElementById('conn-status-text');
  try {
    const res = await fetch('/api/health');
    if (res.ok) {
      if (pill) pill.className = 'conn-pill online';
      if (text) text.textContent = 'Vercel Gateway Online';
    } else {
      if (pill) pill.className = 'conn-pill';
      if (text) text.textContent = 'Gateway Degraded';
    }
  } catch (e) {
    if (pill) pill.className = 'conn-pill';
    if (text) text.textContent = 'Network Offline';
  }
}

// Account Dropdown Synchronizer
function populateAccountDropdowns(accounts) {
  const srcSelect = document.getElementById('transfer-source');
  const destSelect = document.getElementById('transfer-dest');
  if (!accounts || accounts.length === 0) return;

  const optionsHtml = accounts.map(acc => `<option value="${acc}">${acc}</option>`).join('');
  if (srcSelect) {
    const curVal = srcSelect.value;
    srcSelect.innerHTML = optionsHtml;
    if (accounts.includes(curVal)) srcSelect.value = curVal;
  }
  if (destSelect) {
    const curVal = destSelect.value;
    destSelect.innerHTML = optionsHtml;
    if (accounts.includes(curVal)) {
      destSelect.value = curVal;
    } else if (accounts.length > 1) {
      destSelect.value = accounts[1];
    }
  }
}

// Dynamic Taxonomy Synchronizer (Fetches authoritative taxonomy from /api/taxonomy)
async function fetchTaxonomyFromApi() {
  try {
    const res = await fetch('/api/taxonomy');
    if (!res.ok) {
      console.warn('Taxonomy API responded with status', res.status);
      return;
    }
    const json = await res.json();
    if (json.success && json.data) {
      const data = json.data;
      if (data.categoriesByType) {
        Object.keys(data.categoriesByType).forEach(typeKey => {
          TAXONOMY[typeKey] = data.categoriesByType[typeKey];
        });
      }
      if (Array.isArray(data.accounts) && data.accounts.length > 0) {
        state.accounts = data.accounts.map(name => ({
          name,
          type: name.includes('Bank') ? 'Bank Account' : (name.includes('Sacco') ? 'SACCO Account' : (name.includes('Cash') ? 'Cash Wallet' : 'Asset Account')),
          balance: 0.00
        }));
        populateAccountDropdowns(data.accounts);
        renderAccountsAndBudgets();
      }
      console.log('✓ Dynamic taxonomy synchronized successfully from /api/taxonomy:', TAXONOMY);
    }
  } catch (err) {
    console.warn('Could not fetch dynamic taxonomy:', err.message);
  }
}

// Live Transactions Synchronizer (Loads confirmed transactions from Google Sheets via /api/transactions or direct Apps Script fallback)
const FALLBACK_GAS_URL = 'https://script.google.com/macros/s/AKfycbzLo8NZHU3rmGIT6R-une9xrjUqwIdSbUG6to1O_ZwohEbvST1-3MjpNvCaNq2TOF4_Xw/exec';

async function fetchLiveTransactions() {
  try {
    let res = await fetch('/api/transactions?limit=100');
    if (!res.ok) {
      console.warn('Vercel /api/transactions returned', res.status, 'trying direct Apps Script fallback...');
      res = await fetch(`${FALLBACK_GAS_URL}?action=getRecentTransactions&limit=100`);
    }
    if (!res.ok) {
      console.warn('Could not fetch transactions from fallback either, status:', res.status);
      return;
    }
    const json = await res.json();
    if (json.success && Array.isArray(json.data) && json.data.length > 0) {
      state.confirmedRecords = json.data;
      renderLedgerTables();
      updateDashboardMetrics();
      console.log(`✓ Synchronized ${json.data.length} live transactions from Google Sheets`);
    }
  } catch (err) {
    console.warn('Could not fetch live transactions, attempting fallback:', err.message);
    try {
      const fbRes = await fetch(`${FALLBACK_GAS_URL}?action=getRecentTransactions&limit=100`);
      if (fbRes.ok) {
        const json = await fbRes.json();
        if (json.success && Array.isArray(json.data) && json.data.length > 0) {
          state.confirmedRecords = json.data;
          renderLedgerTables();
          updateDashboardMetrics();
        }
      }
    } catch (fbErr) {
      console.warn('Fallback also failed:', fbErr.message);
    }
  }
}

// Live Monthly Dashboard & Account Balances Synchronizer
let currentCatSection = 'Income';

async function fetchMonthlyDashboard() {
  try {
    let res = await fetch('/api/dashboard');
    if (!res.ok) {
      console.warn('Vercel /api/dashboard returned', res.status, 'trying direct Apps Script fallback...');
      res = await fetch(`${FALLBACK_GAS_URL}?action=dashboard`);
    }
    if (!res.ok) {
      console.warn('Could not fetch dashboard from fallback either, status:', res.status);
      return;
    }
    const json = await res.json();
    if (json.success) {
      state.monthlyData = json.month;
      state.accountsData = json.accounts;
      renderMonthlyDashboard();
      renderAccountsAndBudgets();
      renderAccountsBalanceTable();
      console.log('✓ Synchronized live monthly budget and account balances from Google Sheets:', json);
    }
  } catch (err) {
    console.warn('Could not fetch live dashboard:', err.message);
    try {
      const fbRes = await fetch(`${FALLBACK_GAS_URL}?action=dashboard`);
      if (fbRes.ok) {
        const json = await fbRes.json();
        if (json.success) {
          state.monthlyData = json.month;
          state.accountsData = json.accounts;
          renderMonthlyDashboard();
          renderAccountsAndBudgets();
          renderAccountsBalanceTable();
        }
      }
    } catch (fbErr) {
      console.warn('Dashboard fallback error:', fbErr.message);
    }
  }
}

function renderMonthlyDashboard() {
  if (!state.monthlyData) return;
  const m = state.monthlyData;

  const badge = document.getElementById('monthly-sheet-badge');
  const title = document.getElementById('monthly-budget-title');
  if (badge) badge.textContent = `SHEET: ${m.month}`;
  if (title) title.textContent = `Monthly Budget & Tracking (${m.month})`;

  const tiles = m.summaryTiles || {};
  function setTile(prefix, t) {
    const valEl = document.getElementById(`tile-${prefix}-val`);
    const subEl = document.getElementById(`tile-${prefix}-sub`);
    if (valEl && t) {
      valEl.textContent = `Ksh ${Number(t.actual || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
    }
    if (subEl && t) {
      if (t.goal !== undefined) {
        subEl.textContent = `Goal: Ksh ${Number(t.goal || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })} • ${t.statusText || ''}`;
      } else {
        subEl.textContent = t.statusText || 'Remaining balance';
      }
    }
  }

  setTile('income', tiles.totalIncome);
  setTile('bills', tiles.totalBills);
  setTile('debt', tiles.totalDebtPayoff);
  setTile('expenses', tiles.totalExpenses);
  setTile('savings', tiles.totalSavings);

  const unallocEl = document.getElementById('tile-unallocated-val');
  if (unallocEl && tiles.unallocatedIncome) {
    unallocEl.textContent = `Ksh ${Number(tiles.unallocatedIncome.actual || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
  }

  renderCategoryTable(currentCatSection);
}

function renderCategoryTable(section) {
  currentCatSection = section || 'Income';
  const tbody = document.getElementById('month-categories-tbody');
  if (!tbody) return;

  // Update chip active states
  document.querySelectorAll('#cat-tabs button').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-cat-section') === currentCatSection);
  });

  const tables = (state.monthlyData && state.monthlyData.tables) || {};
  const items = tables[currentCatSection] || [];

  if (items.length === 0) {
    tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted">No ${currentCatSection} categories found in monthly sheet.</td></tr>`;
    return;
  }

  tbody.innerHTML = items.map(item => {
    const diffColor = item.diff < 0 ? 'text-amber' : (item.diff > 0 ? 'text-green' : '');
    return `
      <tr>
        <td><strong>${item.category}</strong></td>
        <td>Ksh ${Number(item.goal).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
        <td>Ksh ${Number(item.actual).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
        <td class="${diffColor}">Ksh ${Number(item.diff).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
      </tr>
    `;
  }).join('');
}

function renderAccountsBalanceTable() {
  const tbody = document.getElementById('accounts-balance-tbody');
  if (!tbody || !Array.isArray(state.accountsData)) return;

  if (state.accountsData.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" class="text-center text-muted">No account balances found.</td></tr>`;
    return;
  }

  tbody.innerHTML = state.accountsData.map(a => `
    <tr>
      <td><strong>${a.accountName}</strong></td>
      <td>Ksh ${Number(a.startBalance).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
      <td><strong>Ksh ${Number(a.currentBalance).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</strong></td>
      <td class="text-green">+Ksh ${Number(a.deposits).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
      <td class="text-amber">Ksh ${Number(a.withdrawals).toLocaleString('en-KE', { minimumFractionDigits: 2 })}</td>
    </tr>
  `).join('');
}

// Main Initialization
document.addEventListener('DOMContentLoaded', () => {
  initNavigation();
  renderAccountsAndBudgets();
  renderLedgerTables();
  checkNetworkStatus();
  fetchTaxonomyFromApi();
  fetchLiveTransactions();
  fetchMonthlyDashboard();

  // Category Tabs click handlers
  document.querySelectorAll('#cat-tabs button').forEach(btn => {
    btn.addEventListener('click', () => {
      const sec = btn.getAttribute('data-cat-section');
      renderCategoryTable(sec);
    });
  });

  // Quick SMS Parse button
  const parseBtn = document.getElementById('btn-parse-sms');
  const smsInput = document.getElementById('quick-sms-input');
  if (parseBtn && smsInput) {
    parseBtn.addEventListener('click', () => {
      const text = smsInput.value.trim();
      if (!text) {
        showToast('Please paste an M-PESA SMS first.', 'error');
        return;
      }
      const parsed = parseMpesaMessage(text);
      if (parsed) {
        state.pendingReviews.unshift(parsed);
        renderReviewCards();
        smsInput.value = '';
        showToast(`✓ Parsed code ${parsed.transactionCode} - Staged for confirmation`, 'success');
        switchTab('review');
      } else {
        showToast('⚠️ Could not parse message. Ensure it begins with a valid Confirmed code.', 'error');
      }
    });
  }

  // Quick sample buttons
  document.querySelectorAll('.chip-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const sampleKey = btn.getAttribute('data-sample');
      if (SAMPLE_MESSAGES[sampleKey] && smsInput) {
        smsInput.value = SAMPLE_MESSAGES[sampleKey];
      }
    });
  });

  // Diagnostics re-run button
  const diagBtn = document.getElementById('btn-run-diagnostics');
  if (diagBtn) {
    diagBtn.addEventListener('click', runSystemDiagnostics);
  }

  // Direct transfer stager
  const stageTransferBtn = document.getElementById('btn-stage-transfer');
  if (stageTransferBtn) {
    stageTransferBtn.addEventListener('click', () => {
      const src = document.getElementById('transfer-source').value;
      const dest = document.getElementById('transfer-dest').value;
      const amt = parseFloat(document.getElementById('transfer-amount').value);
      const code = document.getElementById('transfer-code').value.trim() || `TR${Date.now().toString().slice(-8)}`;

      if (isNaN(amt) || amt <= 0) {
        showToast('Please enter a valid transfer amount.', 'error');
        return;
      }

      const tx = {
        transactionCode: code.toUpperCase(),
        amount: amt,
        type: 'Balance',
        category: '',
        description: `Transfer from ${src} to ${dest}`,
        account: src,
        destinationAccount: dest,
        date: new Date().toISOString().split('T')[0],
        time: new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }),
        rawText: `Direct staged balance transfer: KES ${amt} to ${dest}`
      };

      state.pendingReviews.unshift(tx);
      renderReviewCards();
      showToast(`Staged transfer ${tx.transactionCode} for confirmation`, 'success');
      switchTab('review');
    });
  }
});
