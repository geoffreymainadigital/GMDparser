/**
 * Vercel Serverless Function: POST /api/transaction
 * Securely proxies confirmed M-PESA transactions to Google Apps Script
 */

export const maxDuration = 60;

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

  const incomingAuthKey = req.headers['x-gmd-auth-key'] || req.headers['authorization']?.replace(/^Bearer\s+/i, '') || req.body?.authKey;
  const expectedAuthSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';

  if (!expectedAuthSecret) {
    return res.status(500).json({
      success: false,
      status: 'SERVER_MISCONFIGURED',
      error: 'Server misconfigured: GMD_AUTH_SECRET environment variable is not set on gateway.'
    });
  }

  if (incomingAuthKey !== expectedAuthSecret) {
    return res.status(401).json({
      success: false,
      status: 'UNAUTHORIZED',
      error: 'Invalid or missing client authentication key provided.'
    });
  }

  const payload = req.body || {};
  const transaction = payload.transaction || payload;
  if (!transaction || typeof transaction !== 'object') {
    return res.status(400).json({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Invalid transaction payload'
    });
  }

  const appsScriptUrl = process.env.APPS_SCRIPT_URL || 'https://script.google.com/macros/s/AKfycbyeDkG4ytwqhGzgLsePqUT2ME0TJp9QkI4Kn4kAiX9qwafFeKc7LxQXliX4btt2yRu8kA/exec';

  try {
    const authSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';
    const backendPayload = {
      action: payload.action || 'createTransaction',
      authKey: authSecret,
      code: payload.code,
      rows: payload.rows,
      transaction: transaction
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 55000); // 55s timeout for spreadsheet write

    const upstreamRes = await fetch(appsScriptUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-GMD-Auth-Key': authSecret
      },
      body: JSON.stringify(backendPayload),
      redirect: 'follow',
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
