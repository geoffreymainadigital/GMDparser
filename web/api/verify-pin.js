/**
 * Vercel Serverless Function: POST /api/verify-pin
 * Verifies web PIN and sets HTTP-only session cookie
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).json({ success: false, error: 'Method not allowed' });

  const { pin } = req.body || {};
  const expectedPin = process.env.GMD_WEB_PIN || '1234';

  if (!pin || String(pin).trim() !== String(expectedPin).trim()) {
    return res.status(401).json({
      success: false,
      status: 'INVALID_PIN',
      error: 'Invalid PIN entered. Access denied.'
    });
  }

  // Set HTTP-only session cookie for 30 days
  res.setHeader('Set-Cookie', 'gmd_session=authenticated; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000');
  return res.status(200).json({
    success: true,
    status: 'AUTHENTICATED',
    message: 'PIN verified successfully'
  });
}
