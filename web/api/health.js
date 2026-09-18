/**
 * Vercel Serverless Function: GET /api/health
 * Probes gateway health and upstream Google Apps Script connectivity
 */

export default async function handler(req, res) {
  // CORS Preflight
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({
      success: false,
      error: `Method ${req.method} not allowed`
    });
  }

  const startTime = Date.now();
  const appsScriptUrl = process.env.APPS_SCRIPT_URL;

  const healthPayload = {
    status: 'healthy',
    service: 'gmdparser-api',
    version: '1.0.0',
    gateway: 'vercel',
    timestamp: new Date().toISOString(),
    upstream: {
      appsScriptConfigured: !!appsScriptUrl,
      status: 'unknown',
      responseTimeMs: null
    }
  };

  if (appsScriptUrl) {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000);

      const upstreamRes = await fetch(`${appsScriptUrl}?action=health`, {
        method: 'GET',
        signal: controller.signal
      });
      clearTimeout(timeoutId);

      const elapsed = Date.now() - startTime;
      healthPayload.upstream.responseTimeMs = elapsed;

      if (upstreamRes.ok) {
        const upstreamData = await upstreamRes.json().catch(() => null);
        healthPayload.upstream.status = 'connected';
        healthPayload.upstream.details = upstreamData;
      } else {
        healthPayload.status = 'degraded';
        healthPayload.upstream.status = `http_${upstreamRes.status}`;
      }
    } catch (err) {
      healthPayload.status = 'degraded';
      healthPayload.upstream.status = 'unreachable';
      healthPayload.upstream.error = err.name === 'AbortError' ? 'Upstream timeout (8s)' : err.message;
    }
  } else {
    healthPayload.upstream.status = 'unconfigured';
    healthPayload.upstream.note = 'APPS_SCRIPT_URL environment variable is not set yet in Vercel settings';
  }

  return res.status(200).json(healthPayload);
}
