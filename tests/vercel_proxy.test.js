/**
 * Automated Verification for Vercel API Handlers
 */
import assert from 'node:assert';
import healthHandler from '../web/api/health.js';
import taxonomyHandler from '../web/api/taxonomy.js';
import transactionHandler from '../web/api/transaction.js';

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

  // 3. Taxonomy fallback GET
  const req3 = createMockReq({ method: 'GET' });
  const res3 = createMockRes();
  await taxonomyHandler(req3, res3);
  assert.strictEqual(res3.statusCode, 200);
  assert.strictEqual(res3.body.success, true);
  assert.ok(res3.body.data.types.includes('Income'));
  assert.ok(res3.body.data.types.includes('Expenses'));
  console.log('✓ GET /api/taxonomy returned 200 OK with valid types');

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

  console.log('\nAll Vercel API proxy unit tests PASSED successfully.');
}

runTests().catch(err => {
  console.error('Test execution failed:', err);
  process.exit(1);
});
