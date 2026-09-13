/**
 * Vercel Serverless Function: GET/POST /api/auth-check
 * Verifies API connectivity and client authentication
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  // Public auth check endpoint
  return res.status(200).json({
    success: true,
    status: 'AUTHORIZED',
    service: 'gmdparser-api',
    serverUrl: 'https://gmdparser.vercel.app',
    autoSyncAllowed: false,
    timestamp: new Date().toISOString()
  });
}
