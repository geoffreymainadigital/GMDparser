/**
 * GMDParser Web Application Engine
 * Responsive financial operating system client
 */

// Category taxonomy by transaction type
const TAXONOMY = {
  'Income': ['Salary', 'Business Income', 'Dividends', 'Interest', 'Refunds', 'Gifts / Support'],
  'Expenses': ['Groceries', 'Dining Out / Takeout', 'Transport & Fuel', 'Shopping & Clothing', 'Entertainment', 'Personal Care', 'Health & Pharmacy'],
  'Bills': ['Rent', 'Electricity / KPLC', 'Water', 'Internet / WiFi', 'TV & Subscriptions', 'Home Maintenance'],
  'Debt': ['Credit Card', 'Bank Loan Repayment', 'Hustler Fund', 'Personal Loan', 'Mobile Loan (M-Shwari / Fuliza)'],
  'Savings': ['Emergency Fund', 'Money Market Fund (MMF)', 'SACCO Monthly Deposit', 'Fixed Deposit', 'Treasury Bills'],
  'Transfer': ['Internal Account Transfer']
};

const SAMPLE_MESSAGES = {
  kplc: 'TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00.',
  naivas: 'TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00.',
  send: 'TD49DEF789 Confirmed. Ksh2,000.00 sent to JANE DOE 0712345678 on 7/9/26 at 10:05 AM. New M-PESA balance is Ksh9,200.00. Transaction cost, Ksh15.00.',
  income: 'TD50GHI012 Confirmed. You have received Ksh50,000.00 from ACME TECH LTD 0798765432 on 7/9/26 at 9:00 AM. New M-PESA balance is Ksh59,200.00.',
  transfer: 'TD51JKL345 Confirmed. Ksh10,000.00 sent to NCBA LOOP for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh49,200.00.'
};

// Application State
const state = {
  pendingReviews: [],
  confirmedRecords: [],
  accounts: [
    { name: 'M-PESA', type: 'Mobile Money', balance: 12450.00 },
    { name: 'NCBA Loop', type: 'Bank Account', balance: 45200.00 },
    { name: 'Equity Bank', type: 'Bank Account', balance: 18500.00 },
    { name: 'Stima Sacco', type: 'SACCO Account', balance: 120000.00 },
    { name: 'CIC Money Market Fund', type: 'MMF Account', balance: 85000.00 },
    { name: 'Physical Cash', type: 'Cash Wallet', balance: 3200.00 }
  ],
  budgets: [
    { category: 'Groceries', spent: 14500, limit: 20000 },
    { category: 'Transport & Fuel', spent: 8200, limit: 10000 },
    { category: 'Dining Out / Takeout', spent: 4500, limit: 6000 },
    { category: 'Electricity / KPLC', spent: 3500, limit: 4000 },
    { category: 'Internet / WiFi', spent: 3000, limit: 3000 },
    { category: 'Shopping & Clothing', spent: 5500, limit: 5000 }
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

  // 1. Sent to (Paybill / Send Money / Transfer)
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
    let category = 'Gifts / Support';
    let destAccount = null;

    if (lower.includes('kenya power') || lower.includes('kplc')) {
      type = 'Bills'; category = 'Electricity / KPLC';
    } else if (lower.includes('water')) {
      type = 'Bills'; category = 'Water';
    } else if (lower.includes('safaricom home') || lower.includes('zuku') || lower.includes('faiba') || lower.includes('poa')) {
      type = 'Bills'; category = 'Internet / WiFi';
    } else if (lower.includes('ncba loop') || lower.includes('loop')) {
      type = 'Transfer'; category = 'Internal Account Transfer'; destAccount = 'Bank (NCBA Loop)';
    } else if (lower.includes('equity')) {
      type = 'Transfer'; category = 'Internal Account Transfer'; destAccount = 'Bank (Equity)';
    } else if (lower.includes('kcb')) {
      type = 'Transfer'; category = 'Internal Account Transfer'; destAccount = 'Bank (KCB)';
    } else if (lower.includes('sacco') || lower.includes('stima')) {
      type = 'Transfer'; category = 'Internal Account Transfer'; destAccount = 'SACCO Account';
    } else if (lower.includes('cic') || lower.includes('mmf')) {
      type = 'Savings'; category = 'Money Market Fund (MMF)'; destAccount = 'MMF Account';
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
      account: 'M-PESA',
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
    let category = 'Shopping & Clothing';
    if (lower.includes('naivas') || lower.includes('carrefour') || lower.includes('quickmart')) {
      category = 'Groceries';
    } else if (lower.includes('kfc') || lower.includes('java') || lower.includes('artcaffe')) {
      category = 'Dining Out / Takeout';
    } else if (lower.includes('total') || lower.includes('shell') || lower.includes('uber')) {
      category = 'Transport & Fuel';
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
      type: 'Expenses',
      category,
      account: 'M-PESA',
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

    return {
      transactionCode,
      amount,
      balance,
      cost: 0,
      date: formatIsoDate(dateStr),
      time: timeStr,
      sender,
      description: `Received from ${sender}`,
      type: 'Income',
      category: 'Salary',
      account: 'M-PESA',
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
      switchTab(tabId);
    });
  });

  // Jump links
  document.querySelectorAll('.nav-jump').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      const tabId = btn.getAttribute('data-tab');
      switchTab(tabId);
    });
  });

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
    budgets: 'Budgets & Limits',
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

    const isTransfer = tx.type === 'Transfer';
    const categoriesForType = TAXONOMY[tx.type] || ['General'];

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
            ${['Expenses', 'Bills', 'Income', 'Transfer', 'Savings', 'Debt'].map(t =>
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
          <input type="text" value="${tx.account}" id="tx-account-${index}" />
        </div>
        <div class="form-group" id="tx-dest-group-${index}" style="${isTransfer ? '' : 'display: none;'}">
          <label style="color: var(--color-cyan);">Destination Account (Transfer Only)</label>
          <input type="text" value="${tx.destinationAccount || 'Bank (NCBA Loop)'}" id="tx-dest-${index}" placeholder="e.g. Bank (NCBA Loop)" />
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
      const cats = TAXONOMY[selectedType] || ['General'];
      catSelect.innerHTML = cats.map(c => `<option value="${c}">${c}</option>`).join('');
      destGroup.style.display = selectedType === 'Transfer' ? 'block' : 'none';
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
    .sumOf ? 0 : state.confirmedRecords.filter(r => r.type === 'Income' && r.status === 'SYNCED').reduce((acc, c) => acc + c.amount, 0);

  const expenses = state.confirmedRecords
    .filter(r => (r.type === 'Expenses' || r.type === 'Bills') && r.status === 'SYNCED')
    .reduce((acc, c) => acc + c.amount, 0);

  const transfers = state.confirmedRecords
    .filter(r => r.type === 'Transfer' && r.status === 'SYNCED')
    .reduce((acc, c) => acc + c.amount, 0);

  const elInflow = document.getElementById('dash-inflow');
  const elExp = document.getElementById('dash-expenses');
  const elTrans = document.getElementById('dash-transfers');

  if (elInflow) elInflow.textContent = `Ksh ${inflow.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
  if (elExp) elExp.textContent = `Ksh ${expenses.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
  if (elTrans) elTrans.textContent = `Ksh ${transfers.toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
}

// Accounts & Budgets Initializer
function renderAccountsAndBudgets() {
  const accountsGrid = document.getElementById('accounts-grid');
  if (accountsGrid) {
    accountsGrid.innerHTML = state.accounts.map(a => `
      <div class="account-item-card">
        <div>
          <div class="acc-title">${a.name}</div>
          <div class="acc-type">${a.type}</div>
        </div>
        <div class="acc-bal">Ksh ${a.balance.toLocaleString('en-KE', { minimumFractionDigits: 2 })}</div>
      </div>
    `).join('');
  }

  const budgetList = document.getElementById('budget-list');
  if (budgetList) {
    budgetList.innerHTML = state.budgets.map(b => {
      const pct = Math.min(Math.round((b.spent / b.limit) * 100), 100);
      const isExceeded = b.spent > b.limit;
      return `
        <div class="budget-row">
          <div class="budget-info">
            <span>${b.category}</span>
            <span style="color: ${isExceeded ? 'var(--color-red)' : 'var(--text-secondary)'};">
              Ksh ${b.spent.toLocaleString()} / Ksh ${b.limit.toLocaleString()} (${pct}%)
            </span>
          </div>
          <div class="progress-track">
            <div class="progress-bar ${isExceeded ? 'exceeded' : ''}" style="width: ${pct}%;"></div>
          </div>
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

// Main Initialization
document.addEventListener('DOMContentLoaded', () => {
  initNavigation();
  renderAccountsAndBudgets();
  renderLedgerTables();
  checkNetworkStatus();

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
        type: 'Transfer',
        category: 'Internal Account Transfer',
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
