/**
 * Vercel Serverless Function: GET/POST /api/test-connection
 * Provides detailed diagnostics distinguishing gateway reachability from Apps Script backend health
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const startTime = Date.now();
  const appsScriptUrl = 'https://script.google.com/macros/s/AKfycbyeDkG4ytwqhGzgLsePqUT2ME0TJp9QkI4Kn4kAiX9qwafFeKc7LxQXliX4btt2yRu8kA/exec';

  const diagnosticResult = {
    timestamp: new Date().toISOString(),
    vercel: {
      status: 'online',
      productionUrl: 'https://gmdparser.vercel.app',
      nodeVersion: process.version,
      region: process.env.VERCEL_REGION || 'iad1',
      gatewayLatencyMs: 0
    },
    appsScript: {
      configured: !!appsScriptUrl,
      urlRedacted: appsScriptUrl ? appsScriptUrl.substring(0, 35) + '...' : null,
      status: 'unconfigured',
      latencyMs: null,
      details: null
    },
    googleSheets: {
      status: 'unknown',
      sheetName: 'Transactions',
      contractWriteBoundary: 'C:D, G:H, J:L'
    }
  };

  if (!appsScriptUrl) {
    diagnosticResult.appsScript.status = 'unconfigured';
    diagnosticResult.appsScript.message = 'APPS_SCRIPT_URL is not set in Vercel environment variables.';
    return res.status(200).json(diagnosticResult);
  }

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 10000); // 10s timeout

    const gasRes = await fetch(`${appsScriptUrl}?action=health`, {
      method: 'GET',
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    const elapsed = Date.now() - startTime;
    diagnosticResult.appsScript.latencyMs = elapsed;

    if (gasRes.ok) {
      const data = await gasRes.json().catch(() => null);
      diagnosticResult.appsScript.status = 'healthy';
      diagnosticResult.appsScript.details = data;
      if (data && data.sheetExists) {
        diagnosticResult.googleSheets.status = 'connected';
        diagnosticResult.googleSheets.spreadsheetId = data.spreadsheetId;
      } else {
        diagnosticResult.googleSheets.status = 'sheet_missing';
      }
    } else {
      diagnosticResult.appsScript.status = `error_http_${gasRes.status}`;
    }
  } catch (err) {
    diagnosticResult.appsScript.status = err.name === 'AbortError' ? 'timeout_10s' : 'unreachable';
    diagnosticResult.appsScript.error = err.message;
  }

  diagnosticResult.vercel.gatewayLatencyMs = Date.now() - startTime;
  return res.status(200).json(diagnosticResult);
}
