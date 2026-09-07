/**
 * Vercel Serverless Function: GET/POST /api/auth-check
 * Verifies API connectivity and client authentication
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

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

  return res.status(200).json({
    success: true,
    status: 'AUTHORIZED',
    service: 'gmdparser-api',
    serverUrl: 'https://gmdparser.vercel.app',
    autoSyncAllowed: false,
    timestamp: new Date().toISOString()
  });
}
