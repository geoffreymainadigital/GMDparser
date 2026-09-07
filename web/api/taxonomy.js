/**
 * Vercel Serverless Function: GET /api/taxonomy
 * Proxies taxonomy data from Google Sheets or returns baseline defaults
 */

const BASELINE_TAXONOMY = {
  types: ['Income', 'Expenses', 'Bills', 'Debt', 'Savings', 'Transfer'],
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

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({ success: false, error: `Method ${req.method} not allowed` });
  }

  const appsScriptUrl = process.env.APPS_SCRIPT_URL;
  if (!appsScriptUrl) {
    // Return baseline taxonomy if backend not yet wired
    return res.status(200).json({
      success: true,
      source: 'baseline_default',
      data: BASELINE_TAXONOMY,
      syncedAt: new Date().toISOString()
    });
  }

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 8000);

    const upstreamRes = await fetch(`${appsScriptUrl}?action=taxonomy`, {
      method: 'GET',
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    if (upstreamRes.ok) {
      const data = await upstreamRes.json();
      return res.status(200).json({
        success: true,
        source: 'spreadsheet_live',
        data: data.data || data,
        syncedAt: new Date().toISOString()
      });
    }

    // Fallback on upstream non-200
    return res.status(200).json({
      success: true,
      source: 'fallback_after_upstream_error',
      data: BASELINE_TAXONOMY,
      syncedAt: new Date().toISOString()
    });

  } catch (err) {
    return res.status(200).json({
      success: true,
      source: 'fallback_after_timeout',
      data: BASELINE_TAXONOMY,
      syncedAt: new Date().toISOString()
    });
  }
}
