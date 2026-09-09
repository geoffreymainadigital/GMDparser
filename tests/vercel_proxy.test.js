/**
 * Automated Verification for Vercel API Handlers
 */
import assert from 'node:assert';
import healthHandler from '../web/api/health.js';
import taxonomyHandler from '../web/api/taxonomy.js';
import transactionHandler from '../web/api/transaction.js';
import testConnectionHandler from '../web/api/test-connection.js';

// Mock request / response helpers
function createMockReq(options = {}) {
  return {
    method: options.method || 'GET',
    headers: options.headers || {},
    body: options.body || null,
    query: options.query || {}
  };
}

function createMockRes() {
  const res = {
    statusCode: 200,
    headers: {},
    ended: false,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    setHeader(k, v) {
      this.headers[k] = v;
      return this;
    },
    json(data) {
      this.body = data;
      this.ended = true;
      return this;
    },
    end() {
      this.ended = true;
      return this;
    }
  };
  return res;
}

async function runTests() {
  console.log('Running Vercel API proxy unit tests...');

  // 1. Health check GET
  const req1 = createMockReq({ method: 'GET' });
  const res1 = createMockRes();
  await healthHandler(req1, res1);
  assert.strictEqual(res1.statusCode, 200);
  assert.strictEqual(res1.body.status, 'healthy');
  assert.strictEqual(res1.body.service, 'gmdparser-api');
  console.log('✓ GET /api/health returned 200 OK healthy');

  // 2. Health check method not allowed
  const req2 = createMockReq({ method: 'DELETE' });
  const res2 = createMockRes();
  await healthHandler(req2, res2);
  assert.strictEqual(res2.statusCode, 405);
  console.log('✓ DELETE /api/health returned 405 Method Not Allowed');

  // 3. Taxonomy upstream unconfigured (strict fail-closed, no silent fake data)
  delete process.env.APPS_SCRIPT_URL;
  const req3 = createMockReq({ method: 'GET' });
  const res3 = createMockRes();
  await taxonomyHandler(req3, res3);
  assert.strictEqual(res3.statusCode, 503);
  assert.strictEqual(res3.body.status, 'UPSTREAM_NOT_CONFIGURED');
  console.log('✓ GET /api/taxonomy returned 503 when upstream unconfigured (no silent fake fallback)');


  // 4. Transaction missing body
  const req4 = createMockReq({ method: 'POST', body: null });
  const res4 = createMockRes();
  await transactionHandler(req4, res4);
  assert.strictEqual(res4.statusCode, 400);
  assert.strictEqual(res4.body.status, 'BAD_REQUEST');
  console.log('✓ POST /api/transaction rejected empty body with 400');

  // 5. Transaction upstream unconfigured
  const req5 = createMockReq({
    method: 'POST',
    body: {
      action: 'createTransaction',
      transaction: {
        date: '2026-09-07',
        type: 'Expenses',
        category: 'Groceries',
        description: 'Test',
        amount: 100,
        account: 'M-PESA',
        transactionCode: 'TD99TST123'
      }
    }
  });
  const res5 = createMockRes();
  delete process.env.APPS_SCRIPT_URL; // Ensure testing unconfigured branch
  await transactionHandler(req5, res5);
  assert.strictEqual(res5.statusCode, 503);
  assert.strictEqual(res5.body.status, 'UPSTREAM_NOT_CONFIGURED');
  console.log('✓ POST /api/transaction handled unconfigured upstream with 503');

  // 6. Test connection diagnostics endpoint
  const req6 = createMockReq({ method: 'GET' });
  const res6 = createMockRes();
  await testConnectionHandler(req6, res6);
  assert.strictEqual(res6.statusCode, 200);
  assert.strictEqual(res6.body.vercel.status, 'online');
  assert.strictEqual(res6.body.vercel.productionUrl, 'https://gmdparser.vercel.app');
  console.log('✓ GET /api/test-connection returned 200 OK with diagnostic report');

  // 7. Auth check endpoint
  const authHandler = (await import('../web/api/auth-check.js')).default;
  const req7 = createMockReq({ method: 'GET' });
  const res7 = createMockRes();
  await authHandler(req7, res7);
  assert.strictEqual(res7.statusCode, 200);
  assert.strictEqual(res7.body.status, 'AUTHORIZED');
  assert.strictEqual(res7.body.autoSyncAllowed, false);
  console.log('✓ GET /api/auth-check returned 200 OK with autoSyncAllowed=false');

  // 8. Auto-sync SMS rejection (fail-closed invariant)
  const autoSyncHandler = (await import('../web/api/auto-sync-sms.js')).default;
  const req8 = createMockReq({ method: 'POST', body: { rawSms: 'Test SMS' } });
  const res8 = createMockRes();
  await autoSyncHandler(req8, res8);
  assert.strictEqual(res8.statusCode, 403);
  assert.strictEqual(res8.body.status, 'CONFIRMATION_REQUIRED');
  assert.strictEqual(res8.body.isAutoSync, false);
  console.log('✓ POST /api/auto-sync-sms correctly rejected unconfirmed write with 403');

  // 9. Dashboard proxy handler test (unconfigured Apps Script URL returns real 503 error)
  const dashboardHandler = (await import('../web/api/dashboard.js')).default;
  const req9 = createMockReq({ method: 'GET' });
  const res9 = createMockRes();
  delete process.env.APPS_SCRIPT_URL;
  await dashboardHandler(req9, res9);
  assert.strictEqual(res9.statusCode, 503);
  assert.strictEqual(res9.body.success, false);
  assert.strictEqual(res9.body.error, 'APPS_SCRIPT_URL not configured');
  console.log('✓ GET /api/dashboard returns 503 error when APPS_SCRIPT_URL is not set (fail-closed, no fake data)');

  // 10. Dashboard proxy method not allowed test
  const req10 = createMockReq({ method: 'POST' });
  const res10 = createMockRes();
  await dashboardHandler(req10, res10);
  assert.strictEqual(res10.statusCode, 405);
  assert.strictEqual(res10.body.success, false);
  console.log('✓ POST /api/dashboard returns 405 Method Not Allowed');

  console.log('\nAll Vercel API proxy unit tests PASSED successfully.');
}

runTests().catch(err => {
  console.error('Test execution failed:', err);
  process.exit(1);
});

