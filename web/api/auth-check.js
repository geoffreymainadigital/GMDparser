/**
 * Vercel Serverless Function: GET/POST /api/auth-check
 * Verifies API connectivity and client authentication
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method === 'POST') {
    let body = req.body || {};
    if (typeof body === 'string') {
      try { body = JSON.parse(body); } catch (e) {}
    }
    const { action, pin } = body;
    if (action === 'verifyPin' || pin !== undefined) {
      const expectedPin = process.env.GMD_WEB_PIN || '1234';
      if (!pin || String(pin).trim() !== String(expectedPin).trim()) {
        return res.status(401).json({
          success: false,
          status: 'INVALID_PIN',
          error: 'Invalid PIN entered. Access denied.'
        });
      }
      res.setHeader('Set-Cookie', 'gmd_session=authenticated; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000');
      return res.status(200).json({
        success: true,
        status: 'AUTHENTICATED',
        message: 'PIN verified successfully'
      });
    }
  }

  // Public auth check response for GET
  return res.status(200).json({
    success: true,
    status: 'AUTHORIZED',
    service: 'gmdparser-api',
    serverUrl: 'https://gmdparser.vercel.app',
    autoSyncAllowed: false,
    timestamp: new Date().toISOString()
  });
}
