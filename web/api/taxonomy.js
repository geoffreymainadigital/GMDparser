/**
 * Vercel Serverless Function: GET /api/taxonomy
 * Proxies taxonomy data directly from Google Apps Script / Google Sheets
 */

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

  const appsScriptUrl = process.env.APPS_SCRIPT_URL;
  if (!appsScriptUrl) {
    return res.status(503).json({
      success: false,
      status: 'UPSTREAM_NOT_CONFIGURED',
      error: 'APPS_SCRIPT_URL environment variable is not configured on Vercel gateway.',
      note: 'Please deploy the Apps Script backend and add APPS_SCRIPT_URL to Vercel dashboard.'
    });
  }

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 10000);

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

    const errData = await upstreamRes.json().catch(() => null);
    return res.status(upstreamRes.status || 502).json({
      success: false,
      status: 'UPSTREAM_ERROR',
      error: (errData && errData.error) || `Upstream returned status ${upstreamRes.status}`
    });

  } catch (err) {
    const isTimeout = err.name === 'AbortError';
    return res.status(isTimeout ? 504 : 502).json({
      success: false,
      status: isTimeout ? 'GATEWAY_TIMEOUT' : 'BAD_GATEWAY',
      error: isTimeout ? 'Upstream Apps Script request timed out (10s)' : `Upstream connection failure: ${err.message}`
    });
  }
}

