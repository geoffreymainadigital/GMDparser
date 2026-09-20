/**
 * Centralized API Security Guard for Vercel Gateway (/api/*)
 */

export function verifyApiKey(req, res) {
  const apiKey = process.env.GMD_API_KEY || process.env.GMD_AUTH_SECRET;

  if (!apiKey) {
    res.status(500).json({
      success: false,
      status: 'SERVER_MISCONFIGURED',
      error: 'API Key security misconfigured: Neither GMD_API_KEY nor GMD_AUTH_SECRET environment variable is set on Vercel.'
    });
    return false;
  }

  const providedKey = req.headers['x-gmd-api-key'] || req.headers['x-gmd-auth-key'];

  // Check HTTP-only Session Cookie for Web portal requests
  const cookies = req.headers.cookie || '';
  const hasValidSession = cookies.includes('gmd_session=authenticated');

  if ((providedKey && providedKey === apiKey) || hasValidSession) {
    return true;
  }

  res.status(401).json({
    success: false,
    status: 'UNAUTHORIZED',
    error: 'Access denied: Valid X-GMD-API-Key header or authenticated web session required.'
  });
  return false;
}
