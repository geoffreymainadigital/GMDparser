/**
 * Vercel Serverless Function: POST /api/transaction
 * Securely proxies confirmed M-PESA transactions to Google Apps Script
 */

export default async function handler(req, res) {
  // CORS Preflight
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({
      success: false,
      status: 'METHOD_NOT_ALLOWED',
      error: `Method ${req.method} not allowed. Use POST.`
    });
  }

  // Verify client token if configured
  const requiredClientKey = process.env.CLIENT_API_KEY;
  if (requiredClientKey) {
    const providedKey = req.headers['x-gmd-auth-key'];
    if (providedKey !== requiredClientKey) {
      return res.status(401).json({
        success: false,
        status: 'UNAUTHORIZED',
        error: 'Invalid or missing X-GMD-Auth-Key header'
      });
    }
  }

  const payload = req.body;
  if (!payload) {
    return res.status(400).json({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Missing request body'
    });
  }

  const transaction = payload.transaction || payload;
  if (!transaction || typeof transaction !== 'object') {
    return res.status(400).json({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Invalid transaction payload'
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
    const backendPayload = {
      action: payload.action || 'createTransaction',
      authKey: process.env.GMD_API_SECRET || undefined,
      transaction: transaction
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 20000); // 20s timeout for spreadsheet write

    const upstreamRes = await fetch(appsScriptUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-GMD-Auth-Key': process.env.GMD_API_SECRET || ''
      },
      body: JSON.stringify(backendPayload),
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    const rawText = await upstreamRes.text();
    let upstreamData;
    try {
      upstreamData = JSON.parse(rawText);
    } catch (_) {
      const match = rawText.match(/<div[^>]*>([^<]*(?:Error|Exception)[^<]*)<\/div>/i) || rawText.match(/(ReferenceError[^\n<]*)/);
      const gasError = match ? match[1].replace(/&quot;/g, '"') : 'Apps Script returned non-JSON HTML';
      return res.status(502).json({
        success: false,
        status: 'UPSTREAM_SCRIPT_ERROR',
        error: `Apps Script error: ${gasError}. Please check line 27 in code.gs.`
      });
    }

    // Pass through exact status code from Apps Script (e.g. 201, 409, 400)
    const isDuplicate = upstreamData.status === 'DUPLICATE_TRANSACTION_CODE' || upstreamData.status === 'DUPLICATE';
    const statusCode = upstreamRes.status !== 200 ? upstreamRes.status : (upstreamData.success ? 201 : (isDuplicate ? 409 : 400));
    return res.status(statusCode).json(upstreamData);

  } catch (err) {
    const isTimeout = err.name === 'AbortError';
    return res.status(isTimeout ? 504 : 502).json({
      success: false,
      status: isTimeout ? 'GATEWAY_TIMEOUT' : 'BAD_GATEWAY',
      error: isTimeout ? 'Upstream Apps Script request timed out (20s)' : `Upstream connection failure: ${err.message}`
    });
  }
}
