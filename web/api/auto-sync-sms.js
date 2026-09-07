/**
 * Vercel Serverless Function: POST /api/auto-sync-sms
 * Handles SMS sync events. Enforces isAutoSync == false fail-closed invariant.
 */

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({
      success: false,
      error: `Method ${req.method} not allowed. Use POST.`
    });
  }

  const payload = req.body || {};
  const isConfirmed = payload.confirmed === true || payload.isConfirmed === true;

  // Strict Fail-Closed Invariant: Automatic unconfirmed writes are rejected
  if (!isConfirmed) {
    return res.status(403).json({
      success: false,
      status: 'CONFIRMATION_REQUIRED',
      error: 'Direct auto-sync is disabled. Explicit user confirmation is mandatory before spreadsheet recording.',
      isAutoSync: false
    });
  }

  // If confirmed, forward to transaction proxy
  const transactionHandler = (await import('./transaction.js')).default;
  return transactionHandler(req, res);
}
