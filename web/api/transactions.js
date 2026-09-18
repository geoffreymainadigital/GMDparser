/**
 * Vercel Serverless Function: GET /api/transactions
 * Proxies recent transactions directly from Google Apps Script
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).json({ success: false, error: 'Method not allowed' });

  const appsScriptUrl = 'https://script.google.com/macros/s/AKfycbyeDkG4ytwqhGzgLsePqUT2ME0TJp9QkI4Kn4kAiX9qwafFeKc7LxQXliX4btt2yRu8kA/exec';
  if (!appsScriptUrl) return res.status(503).json({ success: false, error: 'APPS_SCRIPT_URL not configured' });

  try {
    const authSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';
    const limit = (req.query && req.query.limit) || 50;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 12000);
    const authParam = authSecret ? `&authKey=${encodeURIComponent(authSecret)}` : '';

    const upstreamRes = await fetch(appsScriptUrl + '?action=getRecentTransactions&limit=' + limit + authParam, {
      method: 'GET',
      headers: {
        'X-GMD-Auth-Key': authSecret
      },
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    if (!upstreamRes.ok) {
      return res.status(upstreamRes.status).json({ success: false, error: 'Upstream returned ' + upstreamRes.status });
    }

    const data = await upstreamRes.json();
    return res.status(200).json(data);
  } catch (err) {
    const isTimeout = err.name === 'AbortError';
    return res.status(isTimeout ? 504 : 502).json({ success: false, error: err.message });
  }
}
