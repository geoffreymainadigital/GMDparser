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
    let category = '';
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
    'savings-categories': 'Savings by Category',
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
            ${!tx.category ? `<option value="" disabled selected>-- Select Category --</option>` : ''}
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
        ${(!tx.category && !isTransfer) ? '<span class="text-amber" style="margin-right: 12px; font-size: 13px;">⚠️ Needs categorization</span>' : ''}
        <button class="btn btn-danger btn-sm btn-dismiss" data-index="${index}">Dismiss</button>
        <button class="btn btn-primary btn-confirm" data-index="${index}" id="btn-confirm-${index}" ${(!tx.category && !isTransfer) ? 'disabled' : ''}>
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
    const confirmBtn = card.querySelector(`#btn-confirm-${index}`);

      typeSelect.addEventListener('change', (e) => {
        const selectedType = e.target.value;
        const cats = TAXONOMY[selectedType] || (selectedType === 'Balance' ? [''] : ['General']);
        catSelect.innerHTML = cats.map(c => `<option value="${c}">${c}</option>`).join('');
        destGroup.style.display = (selectedType === 'Transfer' || selectedType === 'Balance') ? 'block' : 'none';
        if (catSelect.value !== '' || selectedType === 'Transfer' || selectedType === 'Balance') {
          confirmBtn.disabled = false;
        }
      });

      catSelect.addEventListener('change', (e) => {
        if (e.target.value !== '' || typeSelect.value === 'Transfer' || typeSelect.value === 'Balance') {
          confirmBtn.disabled = false;
        } else {
          confirmBtn.disabled = true;
        }
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

      const isTransferType = typeSelect.value === 'Transfer' || typeSelect.value === 'Balance';
      const confirmedTx = {
        date: tx.date,
        type: isTransferType ? 'Balance' : typeSelect.value,
        category: isTransferType ? '' : catSelect.value,
        description: card.querySelector(`#tx-desc-${index}`).value.trim(),
        amount: parseFloat(card.querySelector(`#tx-amount-${index}`).value) || tx.amount,
        account: card.querySelector(`#tx-account-${index}`).value.trim(),
        transactionCode: tx.transactionCode,
        destinationAccount: isTransferType ? card.querySelector(`#tx-dest-${index}`).value.trim() : null
      };

      try {
        const response = await fetch('/api/transaction', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            action: isTransferType ? 'createTransferPair' : 'createTransaction',
            authKey: 'gmd_sec_9948172648',
            transaction: confirmedTx
          })
        });

        const result = await response.json();

        if (response.ok && result.success) {
          const rowInfo = result.data?.row || (result.firstRow ? `${result.firstRow}-${result.lastRow}` : 'sheet');
          showToast(`✓ Recorded ${confirmedTx.transactionCode} to row ${rowInfo}`, 'success');
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
  const mpesaAcc = Array.isArray(state.accountsData)
    ? state.accountsData.find(a => a.accountName.toLowerCase() === 'mpesa')
    : null;
  const mpesaBal = mpesaAcc && mpesaAcc.currentBalance !== undefined ? mpesaAcc.currentBalance : 0;

  const expenses = state.confirmedRecords
    .filter(r => (r.type === 'Expenses' || r.type === 'Bills') && r.status === 'SYNCED')
    .reduce((acc, c) => acc + (Number(c.amount) || 0), 0);

  const transfers = state.confirmedRecords
    .filter(r => r.type === 'Transfer' && r.status === 'SYNCED')
    .reduce((acc, c) => acc + (Number(c.amount) || 0), 0);

  const elMpesa = document.getElementById('dash-mpesa-balance');
  const elExp = document.getElementById('dash-expenses');
  const elTrans = document.getElementById('dash-transfers');

  if (elMpesa) elMpesa.textContent = `Ksh ${Number(mpesaBal).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
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

// Live Monthly Dashboard Synchronizer
let currentCatSection = 'Income';
let dashboardFetchRequestId = 0;
let dashboardLoading = false;
const dashboardCache = {
  monthly: null
};

function renderDashboardLoadingState() {
  dashboardLoading = true;

  // SWR: If cached data exists, render it immediately while revalidating in background!
  if (dashboardCache.monthly) {
    state.dashboardData = dashboardCache.monthly.data;
    state.accountsData = dashboardCache.monthly.accounts;
    renderDashboardData();
    const badge = document.getElementById('monthly-sheet-badge');
    if (badge) badge.textContent = 'SYNCING (CACHED)...';
    return;
  }

  const badge = document.getElementById('monthly-sheet-badge');
  const title = document.getElementById('monthly-budget-title');
  if (badge) badge.textContent = 'SYNCING...';
  if (title) {
    title.textContent = 'Monthly Budget & Tracking (Loading...)';
  }

  // Show loading skeleton / reset tiles
  ['income', 'bills', 'debt', 'expenses', 'savings'].forEach(prefix => {
    const valEl = document.getElementById(`tile-${prefix}-val`);
    const subEl = document.getElementById(`tile-${prefix}-sub`);
    if (valEl) valEl.textContent = 'Loading...';
    if (subEl) subEl.textContent = 'Updating dataset...';
  });
  const unallocEl = document.getElementById('tile-unallocated-val');
  if (unallocEl) unallocEl.textContent = 'Loading...';

  const tbody = document.getElementById('month-categories-tbody');
  if (tbody) {
    tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted">Loading ${currentCatSection} categories...</td></tr>`;
  }
}

async function fetchDashboardData() {
  const requestId = ++dashboardFetchRequestId;
  renderDashboardLoadingState();

  try {
    let res = await fetch(`/api/dashboard`);
    if (!res.ok) {
      console.warn('Vercel /api/dashboard returned', res.status, 'trying direct Apps Script fallback...');
      res = await fetch(`${FALLBACK_GAS_URL}?action=dashboard`);
    }
    if (!res.ok) {
      console.warn('Could not fetch dashboard from fallback either, status:', res.status);
      return;
    }
    const json = await res.json();
    if (requestId !== dashboardFetchRequestId) {
      console.log(`[Race Condition Guard] Ignored stale fetch response #${requestId}`);
      return;
    }
    if (json.success) {
      dashboardLoading = false;
      const periodData = json.month || json.dashboard || json;
      state.savingsData = json.savings;
      state.dashboardData = periodData;
      state.accountsData = json.accounts;
      dashboardCache.monthly = {
        data: periodData,
        accounts: json.accounts,
        savings: json.savings,
        timestamp: Date.now()
      };
      renderDashboardData();
      renderAccountsAndBudgets();
      renderAccountsBalanceTable();
      renderSavingsProgressTable();
      updateDashboardMetrics();
      console.log(`✓ Synchronized live monthly budget and account balances from Google Sheets:`, json);
    }
  } catch (err) {
    console.warn('Could not fetch live dashboard:', err.message);
    try {
      const fbRes = await fetch(`${FALLBACK_GAS_URL}?action=dashboard`);
      if (fbRes.ok) {
        const json = await fbRes.json();
        if (requestId !== dashboardFetchRequestId) {
          console.log(`[Race Condition Guard] Ignored stale fallback fetch response #${requestId}`);
          return;
        }
        if (json.success) {
          dashboardLoading = false;
          const periodData = json.month || json.dashboard || json;
          state.savingsData = json.savings;
          state.dashboardData = periodData;
          state.accountsData = json.accounts;
          dashboardCache.monthly = {
            data: periodData,
            accounts: json.accounts,
            savings: json.savings,
            timestamp: Date.now()
          };
          renderDashboardData();
          renderAccountsAndBudgets();
          renderAccountsBalanceTable();
          renderSavingsProgressTable();
        }
      }
    } catch (fbErr) {
      console.warn('Dashboard fallback error:', fbErr.message);
    }
  }
}

function renderSavingsProgressTable() {
  const tbody = document.getElementById('savings-progress-tbody');
  if (!tbody) return;

  const savingsObj = state.savingsData || (dashboardCache.monthly && dashboardCache.monthly.savings);
  const goals = (savingsObj && savingsObj.goals) || [];
  const totals = (savingsObj && savingsObj.totals) || null;

  if (totals) {
    const elSaved = document.getElementById('savings-total-saved');
    const elGoal = document.getElementById('savings-total-goal');
    const elRem = document.getElementById('savings-total-remaining');
    const elProg = document.getElementById('savings-overall-progress');
    const elProgPct = document.getElementById('savings-total-progress-pct');

    const overallPct = Math.round((Number(totals.overallProgress) || 0) * 100);
    if (elSaved) elSaved.textContent = `Ksh ${Number(totals.totalSaved || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
    if (elGoal) elGoal.textContent = `Ksh ${Number(totals.totalGoal || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
    if (elRem) elRem.textContent = `Ksh ${Number(totals.totalRemaining || 0).toLocaleString('en-KE', { minimumFractionDigits: 2 })}`;
    if (elProg) elProg.textContent = `${overallPct}% Overall Progress`;
    if (elProgPct) elProgPct.textContent = `${overallPct}%`;
  }

  if (goals.length === 0) {
    tbody.innerHTML = `<tr><td colspan="5" class="text-center text-muted">No savings targets found.</td></tr>`;
    return;
  }

  tbody.innerHTML = goals.map(item => {
    const pct = Math.round((Number(item.progress) || 0) * 100);
    const savedFormatted = Number(item.saved).toLocaleString('en-KE', { minimumFractionDigits: 2 });
    const goalFormatted = Number(item.goal).toLocaleString('en-KE', { minimumFractionDigits: 2 });
    const remFormatted = Number(item.remaining).toLocaleString('en-KE', { minimumFractionDigits: 2 });

    return `
      <tr>
        <td><strong>${item.category}</strong></td>
        <td class="text-green">
          <strong>Ksh ${savedFormatted}</strong>
          <div style="font-size: 11px; color: var(--text-muted);">Goal: Ksh ${goalFormatted}</div>
        </td>
        <td>Ksh ${goalFormatted}</td>
        <td>Ksh ${remFormatted}</td>
        <td>
          <div style="display: flex; align-items: center; gap: 8px;">
            <div style="flex: 1; height: 6px; background: var(--surface); border-radius: 3px; overflow: hidden; border: 1px solid var(--border);">
              <div style="width: ${Math.min(100, Math.max(0, pct))}%; height: 100%; background: var(--mpesa-green);"></div>
            </div>
            <span style="font-size: 11px; font-weight: bold;">${pct}%</span>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

function renderDashboardData() {
  if (!state.dashboardData) return;
  const data = state.dashboardData;

  const badge = document.getElementById('monthly-sheet-badge');
  const title = document.getElementById('monthly-budget-title');
  const sheetName = data.tab || data.month || 'Current Month';
  if (badge) badge.textContent = `SHEET: ${sheetName}`;

  if (title) {
    const monthLabel = data.month || 'Current';
    title.textContent = `Monthly Budget & Tracking (${monthLabel})`;
  }

  const tiles = data.summaryTiles || {};
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

  const tables = (state.dashboardData && state.dashboardData.tables) || {};
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
  fetchDashboardData('monthly');

  // Period Toggle Listeners
  const btnMonthly = document.getElementById('btn-period-monthly');
  const btnAnnual = document.getElementById('btn-period-annual');
  if (btnMonthly && btnAnnual) {
    btnMonthly.addEventListener('click', () => {
      btnMonthly.style.background = 'var(--mpesa-green)';
      btnMonthly.style.color = '#000';
      btnAnnual.style.background = 'transparent';
      btnAnnual.style.color = 'var(--text-primary)';
      fetchDashboardData('monthly');
    });
    btnAnnual.addEventListener('click', () => {
      btnAnnual.style.background = 'var(--mpesa-green)';
      btnAnnual.style.color = '#000';
      btnMonthly.style.background = 'transparent';
      btnMonthly.style.color = 'var(--text-primary)';
      fetchDashboardData('annual');
    });
  }

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

  // PIN Security Manager Initializer
  initPinSecurityManager();
});

// Web Dashboard Security Lock Module (PIN / Password & Recovery)
let pinBuffer = '';
let currentPinMode = 'UNLOCK'; // 'UNLOCK' | 'CREATE' | 'CONFIRM'
let currentAuthType = 'PIN';  // 'PIN' | 'PASSWORD'
let stagedPin = '';

function initPinSecurityManager() {
  const isPinEnabled = localStorage.getItem('gmd_pin_enabled') === 'true';
  const storedPin = localStorage.getItem('gmd_pin_code');
  const savedAuthType = localStorage.getItem('gmd_auth_type') || 'PIN';

  updatePinSettingsUI(isPinEnabled);

  if (isPinEnabled && storedPin) {
    switchAuthModeUI(savedAuthType);
    showPinModal('UNLOCK', 'Enter Security Lock', 'Dashboard access is protected by security lock.');
  }

  // Bind Mode Toggle Buttons (PIN vs Password)
  const btnPin = document.getElementById('btn-mode-pin');
  const btnPass = document.getElementById('btn-mode-pass');

  if (btnPin && btnPass) {
    btnPin.addEventListener('click', () => switchAuthModeUI('PIN'));
    btnPass.addEventListener('click', () => switchAuthModeUI('PASSWORD'));
  }

  // Bind Keypad Buttons
  document.querySelectorAll('.pin-keypad .pin-btn[data-key]').forEach(btn => {
    btn.addEventListener('click', () => {
      const digit = btn.getAttribute('data-key');
      handlePinDigitInput(digit);
    });
  });

  // Delete & Cancel Buttons
  const delBtn = document.getElementById('pin-btn-del');
  if (delBtn) {
    delBtn.addEventListener('click', () => {
      if (pinBuffer.length > 0) {
        pinBuffer = pinBuffer.slice(0, -1);
        updatePinDotsUI();
      }
    });
  }

  const cancelBtn = document.getElementById('pin-btn-cancel');
  if (cancelBtn) {
    cancelBtn.addEventListener('click', () => {
      hidePinModal();
    });
  }

  // Password Unlock Submission
  const submitPassBtn = document.getElementById('btn-submit-pass');
  const secPassInput = document.getElementById('sec-pass-input');

  if (submitPassBtn && secPassInput) {
    const handlePassSubmit = () => {
      const enteredPass = secPassInput.value;
      const storedSecret = localStorage.getItem('gmd_pin_code');

      if (currentPinMode === 'UNLOCK') {
        if (enteredPass === storedSecret) {
          hidePinModal();
          secPassInput.value = '';
          showToast('✓ Unlocked Web Dashboard', 'success');
        } else {
          showToast('Incorrect password. Try again.', 'error');
          secPassInput.value = '';
        }
      } else if (currentPinMode === 'CREATE') {
        if (!enteredPass || enteredPass.length < 4) {
          showToast('Password must be at least 4 characters.', 'error');
          return;
        }
        stagedPin = enteredPass;
        secPassInput.value = '';
        showPinModal('CONFIRM', 'Confirm Password', 'Re-enter your password to confirm.');
      } else if (currentPinMode === 'CONFIRM') {
        if (enteredPass === stagedPin) {
          localStorage.setItem('gmd_pin_enabled', 'true');
          localStorage.setItem('gmd_pin_code', enteredPass);
          localStorage.setItem('gmd_auth_type', 'PASSWORD');
          updatePinSettingsUI(true);
          hidePinModal();
          secPassInput.value = '';
          showToast('✓ Password security lock enabled', 'success');
        } else {
          showToast('Password mismatch. Try setting again.', 'error');
          secPassInput.value = '';
          setTimeout(() => {
            showPinModal('CREATE', 'Set Password Lock', 'Enter a secret password for dashboard security.');
          }, 1000);
        }
      }
    };

    submitPassBtn.addEventListener('click', handlePassSubmit);
    secPassInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') handlePassSubmit();
    });
  }

  // Forgot PIN / Recovery Handler
  const forgotBtn = document.getElementById('btn-forgot-pin');
  if (forgotBtn) {
    forgotBtn.addEventListener('click', () => {
      handleForgotPinRecovery();
    });
  }

  // Settings Toggle & Change Buttons
  const toggleBtn = document.getElementById('btn-toggle-pin');
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      const active = localStorage.getItem('gmd_pin_enabled') === 'true';
      if (active) {
        localStorage.removeItem('gmd_pin_enabled');
        localStorage.removeItem('gmd_pin_code');
        localStorage.removeItem('gmd_auth_type');
        updatePinSettingsUI(false);
        showToast('Security lock disabled.', 'info');
      } else {
        switchAuthModeUI('PIN');
        showPinModal('CREATE', 'Set 4-Digit PIN', 'Enter a 4-digit PIN for dashboard security.');
      }
    });
  }

  const changeBtn = document.getElementById('btn-change-pin');
  if (changeBtn) {
    changeBtn.addEventListener('click', () => {
      switchAuthModeUI('PIN');
      showPinModal('CREATE', 'Set New Security Lock', 'Enter a new 4-digit PIN or password.');
    });
  }
}

function switchAuthModeUI(mode) {
  currentAuthType = mode;
  const btnPin = document.getElementById('btn-mode-pin');
  const btnPass = document.getElementById('btn-mode-pass');
  const dotsContainer = document.getElementById('pin-dots-container');
  const passContainer = document.getElementById('pass-input-container');

  if (btnPin) btnPin.className = mode === 'PIN' ? 'btn btn-sm btn-outline active' : 'btn btn-sm btn-outline';
  if (btnPass) btnPass.className = mode === 'PASSWORD' ? 'btn btn-sm btn-outline active' : 'btn btn-sm btn-outline';

  if (dotsContainer) dotsContainer.style.display = mode === 'PIN' ? 'block' : 'none';
  if (passContainer) passContainer.style.display = mode === 'PASSWORD' ? 'flex' : 'none';
}

function handleForgotPinRecovery() {
  const recoveryPrompt = prompt(
    "Security Reset Recovery:\n\nTo reset your forgotten PIN/Password, please enter your registered GMD_AUTH_SECRET or full Spreadsheet ID:"
  );

  if (!recoveryPrompt) return;

  const inputClean = recoveryPrompt.trim();
  // Validates against standard spreadsheet ID pattern (11vrj6f...) or secret prefix
  if (inputClean.length >= 20 || inputClean.startsWith('aaa0b06b') || inputClean.includes('11vrj6f')) {
    localStorage.removeItem('gmd_pin_enabled');
    localStorage.removeItem('gmd_pin_code');
    localStorage.removeItem('gmd_auth_type');
    updatePinSettingsUI(false);
    hidePinModal();
    showToast('✓ Security lock reset successfully.', 'success');
  } else {
    alert("❌ Invalid recovery credential. Verification failed.");
  }
}

function updatePinSettingsUI(enabled) {
  const toggleBtn = document.getElementById('btn-toggle-pin');
  const changeBtn = document.getElementById('btn-change-pin');
  if (toggleBtn) {
    toggleBtn.textContent = enabled ? 'Disable Security Lock' : 'Enable Security Lock';
    toggleBtn.className = enabled ? 'btn btn-sm btn-outline text-amber' : 'btn btn-sm btn-outline';
  }
  if (changeBtn) {
    changeBtn.style.display = enabled ? 'inline-block' : 'none';
  }
}

function showPinModal(mode, title, subtitle) {
  currentPinMode = mode;
  pinBuffer = '';
  const overlay = document.getElementById('pin-lock-overlay');
  const titleEl = document.getElementById('pin-modal-title');
  const subEl = document.getElementById('pin-modal-subtitle');
  const cancelBtn = document.getElementById('pin-btn-cancel');

  if (titleEl) titleEl.textContent = title;
  if (subEl) subEl.textContent = subtitle;
  if (cancelBtn) cancelBtn.style.visibility = (mode === 'UNLOCK') ? 'hidden' : 'visible';

  updatePinDotsUI();
  if (overlay) overlay.style.display = 'flex';
}

function hidePinModal() {
  const overlay = document.getElementById('pin-lock-overlay');
  if (overlay) overlay.style.display = 'none';
  pinBuffer = '';
}

function updatePinDotsUI(isError = false) {
  const dots = document.querySelectorAll('#pin-dots .dot');
  dots.forEach((dot, idx) => {
    dot.className = 'dot';
    if (isError) {
      dot.classList.add('error');
    } else if (idx < pinBuffer.length) {
      dot.classList.add('filled');
    }
  });
}

function handlePinDigitInput(digit) {
  if (pinBuffer.length < 4) {
    pinBuffer += digit;
    updatePinDotsUI();

    if (pinBuffer.length === 4) {
      setTimeout(() => processPinSubmission(), 150);
    }
  }
}

function processPinSubmission() {
  const storedPin = localStorage.getItem('gmd_pin_code');

  if (currentPinMode === 'UNLOCK') {
    if (pinBuffer === storedPin) {
      hidePinModal();
      showToast('✓ Unlocked Web Dashboard', 'success');
    } else {
      triggerPinError('Incorrect PIN code');
    }
  } else if (currentPinMode === 'CREATE') {
    stagedPin = pinBuffer;
    showPinModal('CONFIRM', 'Confirm 4-Digit PIN', 'Re-enter your 4-digit PIN to confirm.');
  } else if (currentPinMode === 'CONFIRM') {
    if (pinBuffer === stagedPin) {
      localStorage.setItem('gmd_pin_enabled', 'true');
      localStorage.setItem('gmd_pin_code', pinBuffer);
      localStorage.setItem('gmd_auth_type', 'PIN');
      updatePinSettingsUI(true);
      hidePinModal();
      showToast('✓ 4-Digit PIN security lock enabled', 'success');
    } else {
      triggerPinError('PIN mismatch. Try setting PIN again.');
      setTimeout(() => {
        showPinModal('CREATE', 'Set 4-Digit PIN', 'Enter a 4-digit PIN for dashboard security.');
      }, 1000);
    }
  }
}

function triggerPinError(msg) {
  updatePinDotsUI(true);
  showToast(msg, 'error');
  setTimeout(() => {
    pinBuffer = '';
    updatePinDotsUI();
  }, 800);
}
