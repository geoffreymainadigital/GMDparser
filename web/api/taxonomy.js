/**
 * Vercel Serverless Function: GET /api/taxonomy
 * Proxies taxonomy data directly from Google Apps Script / Google Sheets
 */

const PRODUCTION_SPREADSHEET_ID = '11vrj6f_S15fk0B5_HVa88Z2BE8u-f207P16N59zMXG4';

export async function fetchLiveSpreadsheetTaxonomy(spreadsheetId = PRODUCTION_SPREADSHEET_ID) {
  const url = `https://docs.google.com/spreadsheets/d/${spreadsheetId}/gviz/tq?tqx=out:json&sheet=Set%20up%20data%202&tq=${encodeURIComponent('select B, E limit 200')}`;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Google Sheets gviz responded with HTTP ${res.status}`);
  }
  const text = await res.text();
  const jsonStr = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1);
  const data = JSON.parse(jsonStr);

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
  data.table.rows.forEach(r => {
    const b = r.c[0] && r.c[0].v ? String(r.c[0].v).trim() : '';
    const e = r.c[1] && r.c[1].v ? String(r.c[1].v).trim() : '';

    if (b === 'Bills and Subscription Title') { currentSection = 'Bills'; return; }
    if (b === 'Debt Title') { currentSection = 'Debt'; return; }
    if (b === 'Expenses Title') { currentSection = 'Expenses'; return; }
    if (b === 'Savings Title') { currentSection = 'Savings'; return; }
    if (b === 'Bank Accounts Title') { currentSection = 'Accounts'; return; }
    if (b === 'Start month' || b.startsWith('Month ')) { currentSection = 'Done'; return; }

    if (currentSection === 'Income') {
      const item = e || b;
      if (item && !item.includes('Title') && !categoriesByType.Income.includes(item)) {
        categoriesByType.Income.push(item);
      }
    } else if (currentSection === 'Accounts') {
      if (b && !b.includes('Title') && isNaN(Number(b)) && !accounts.includes(b)) {
        accounts.push(b);
      }
    } else if (currentSection !== 'Done' && categoriesByType[currentSection]) {
      if (b && !b.includes('Title') && isNaN(Number(b)) && !categoriesByType[currentSection].includes(b)) {
        categoriesByType[currentSection].push(b);
      }
    }
  });

  return {
    types: ['Income', 'Bills', 'Debt', 'Expenses', 'Savings', 'Balance'],
    categoriesByType,
    categories: categoriesByType,
    accounts,
    destinationAccounts: accounts
  };
}

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({
      success: false,
      status: 'METHOD_NOT_ALLOWED',
      error: `Method ${req.method} not allowed. Use GET.`
    });
  }

  const appsScriptUrl = process.env.APPS_SCRIPT_URL || 'https://script.google.com/macros/s/AKfycbyeDkG4ytwqhGzgLsePqUT2ME0TJp9QkI4Kn4kAiX9qwafFeKc7LxQXliX4btt2yRu8kA/exec';
  if (!appsScriptUrl) {
    return res.status(503).json({
      success: false,
      status: 'UPSTREAM_NOT_CONFIGURED',
      error: 'APPS_SCRIPT_URL environment variable is not configured on Vercel gateway.',
      note: 'Please deploy the Apps Script backend and add APPS_SCRIPT_URL to Vercel dashboard.'
    });
  }

  // 1. Try Apps Script upstream first
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 6000);

    const upstreamRes = await fetch(`${appsScriptUrl}?action=taxonomy`, {
      method: 'GET',
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    if (upstreamRes.ok) {
      const rawText = await upstreamRes.text();
      try {
        const json = JSON.parse(rawText);
        const data = json.data || json;
        const cats = data.categoriesByType || data.categories;
        const hasCompleteCategories = cats && cats.Expenses && cats.Expenses.length >= 5;
        if (data && data.types && Array.isArray(data.types) && hasCompleteCategories) {
          return res.status(200).json({
            success: true,
            source: 'apps_script_live',
            data: {
              types: data.types,
              categoriesByType: cats,
              categories: cats,
              accounts: data.accounts,
              destinationAccounts: data.destinationAccounts || data.accounts
            },
            syncedAt: new Date().toISOString()
          });
        }
      } catch (_) {
        // Upstream returned non-JSON (e.g. Google error page) - proceed to live spreadsheet read
      }
    }
  } catch (_) {
    // Upstream timed out or errored - proceed to live spreadsheet read
  }

  // 2. Query the live Google Spreadsheet directly
  try {
    const liveTaxonomy = await fetchLiveSpreadsheetTaxonomy();
    return res.status(200).json({
      success: true,
      source: 'spreadsheet_direct_live',
      data: liveTaxonomy,
      syncedAt: new Date().toISOString()
    });
  } catch (sheetErr) {
    return res.status(502).json({
      success: false,
      status: 'BAD_GATEWAY',
      error: `Failed to fetch live spreadsheet taxonomy: ${sheetErr.message}`
    });
  }
}
