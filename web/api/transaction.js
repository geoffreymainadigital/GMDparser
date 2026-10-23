/**
 * Vercel Serverless Function: POST /api/transaction
 * Securely proxies confirmed M-PESA transactions to Google Apps Script
 */

export const maxDuration = 60;

import { verifyApiKey } from './auth-guard.js';

export default async function handler(req, res) {
  const tStart = performance.now();
  // CORS Preflight
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (!verifyApiKey(req, res)) return;

  if (req.method !== 'POST') {
    return res.status(405).json({
      success: false,
      status: 'METHOD_NOT_ALLOWED',
      error: `Method ${req.method} not allowed. Use POST.`
    });
  }

  if (!req.body || typeof req.body !== 'object' || Object.keys(req.body).length === 0) {
    return res.status(400).json({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Invalid or missing transaction payload'
    });
  }

  const payload = req.body;
  const requestedAction = payload.action || 'createTransaction';

  const appsScriptUrl = process.env.APPS_SCRIPT_URL;
  if (!appsScriptUrl) {
    const totalGatewayMs = Math.round(performance.now() - tStart);
    res.setHeader('Server-Timing', `gateway;dur=${totalGatewayMs}`);
    return res.status(503).json({
      success: false,
      status: 'UPSTREAM_NOT_CONFIGURED',
      error: 'APPS_SCRIPT_URL environment variable is not configured on Vercel gateway.',
      gatewayTiming: {
        totalGatewayMs
      }
    });
  }

  if (requestedAction === 'deleteSingleRowByCode') {
    const authSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';
    try {
      const upstreamRes = await fetch(appsScriptUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify({ action: 'deleteSingleRowByCode', authKey: authSecret, code: payload.code }),
        redirect: 'follow'
      });
      const upstreamData = await upstreamRes.json();
      return res.status(upstreamRes.status).json(upstreamData);
    } catch (err) {
      return res.status(502).json({ success: false, error: err.message });
    }
  }

  // ── Batch path ──────────────────────────────────────────────────────────────
  // Both single and batch requests arrive at /api/transaction (same endpoint).
  // When the Android client sends action=batchCreateTransactions we must forward
  // the full transactions[] array to Apps Script unchanged; the single-transaction
  // path below would silently discard it and degrade batches to one-row writes.
  if (requestedAction === 'batchCreateTransactions') {
    if (!Array.isArray(payload.transactions) || payload.transactions.length === 0) {
      return res.status(400).json({
        success: false,
        status: 'BAD_REQUEST',
        error: 'batchCreateTransactions requires a non-empty transactions array'
      });
    }
    const authSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';
    const batchPayload = {
      action: 'batchCreateTransactions',
      authKey: authSecret,
      transactions: payload.transactions
    };
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 55000);
      const upstreamRes = await fetch(appsScriptUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify(batchPayload),
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
          error: `Apps Script batch error: ${gasError}`
        });
      }
      const totalGatewayMs = Math.round(performance.now() - tStart);
      res.setHeader('Server-Timing', `gateway;dur=${totalGatewayMs}`);
      if (upstreamData && typeof upstreamData === 'object' && !Array.isArray(upstreamData)) {
        upstreamData.gatewayTiming = { totalGatewayMs };
      }
      return res.status(upstreamRes.status === 201 ? 201 : (upstreamData.success ? 201 : 400)).json(upstreamData);
    } catch (err) {
      const isTimeout = err.name === 'AbortError';
      const totalGatewayMs = Math.round(performance.now() - tStart);
      res.setHeader('Server-Timing', `gateway;dur=${totalGatewayMs}`);
      return res.status(isTimeout ? 504 : 502).json({
        success: false,
        status: isTimeout ? 'GATEWAY_TIMEOUT' : 'BAD_GATEWAY',
        error: isTimeout ? 'Batch upstream request timed out (55s)' : `Batch upstream error: ${err.message}`,
        gatewayTiming: { totalGatewayMs }
      });
    }
  }
  // ── End batch path ───────────────────────────────────────────────────────────

  const transaction = payload.transaction || payload;
  if (!transaction || typeof transaction !== 'object') {
    return res.status(400).json({
      success: false,
      status: 'BAD_REQUEST',
      error: 'Invalid transaction payload'
    });
  }

  try {
    const destAcc = payload.destinationAccount || (transaction && transaction.destinationAccount) || null;
    const txObj = {
      ...transaction,
      ...(destAcc ? { destinationAccount: destAcc } : {})
    };
    const authSecret = process.env.GMD_AUTH_SECRET || process.env.GMD_API_SECRET || process.env.APPS_SCRIPT_AUTH_SECRET || '';
    const backendPayload = {
      // Forward the action as-is (createTransaction or createTransferPair).
      // Defaulting to 'createTransaction' keeps backward-compat; Apps Script
      // detects destinationAccount on the transaction object and calls
      // handleCreateTransferPair automatically, so either action value works.
      action: (requestedAction === 'createTransferPair') ? 'createTransferPair' : 'createTransaction',
      authKey: authSecret,
      code: payload.code,
      rows: payload.rows,
      ...(destAcc ? { destinationAccount: destAcc } : {}),
      transaction: txObj
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 55000); // 55s timeout for spreadsheet write

    const upstreamRes = await fetch(appsScriptUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'text/plain;charset=utf-8'
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

    const totalGatewayMs = Math.round(performance.now() - tStart);
    res.setHeader('Server-Timing', `gateway;dur=${totalGatewayMs}`);

    // If upstreamData is an object, attach safe gateway timing metadata (non-sensitive)
    if (upstreamData && typeof upstreamData === 'object' && !Array.isArray(upstreamData)) {
      upstreamData.gatewayTiming = {
        totalGatewayMs
      };
    }

    return res.status(statusCode).json(upstreamData);

  } catch (err) {
    const isTimeout = err.name === 'AbortError';
    const totalGatewayMs = Math.round(performance.now() - tStart);
    res.setHeader('Server-Timing', `gateway;dur=${totalGatewayMs}`);
    return res.status(isTimeout ? 504 : 502).json({
      success: false,
      status: isTimeout ? 'GATEWAY_TIMEOUT' : 'BAD_GATEWAY',
      error: isTimeout ? 'Upstream Apps Script request timed out (55s)' : `Upstream connection failure: ${err.message}`,
      gatewayTiming: {
        totalGatewayMs
      }
    });
  }
}
