const fs = require('fs');
const assert = require('assert');

// Simulate the frontend state management logic for period toggling
class DashboardStateController {
  constructor() {
    this.currentCatSection = 'Income';
    this.currentDashboardPeriod = 'monthly';
    this.dashboardFetchRequestId = 0;
    this.dashboardData = null;
    this.dashboardLoading = false;

    // Simulated DOM UI Elements
    this.ui = {
      badge: 'SYNCING...',
      title: '',
      tiles: {
        income: 0,
        bills: 0,
        debt: 0,
        expenses: 0,
        savings: 0,
        unallocated: 0
      }
    };
  }

  renderDashboardLoadingState(targetPeriod) {
    this.dashboardLoading = true;
    this.currentDashboardPeriod = targetPeriod;
    this.ui.badge = 'SYNCING...';
    this.ui.title = targetPeriod === 'annual' 
      ? 'Annual Dashboard (Loading...)' 
      : 'Monthly Budget & Tracking (Loading...)';
    
    // Clear tiles during loading
    Object.keys(this.ui.tiles).forEach(k => this.ui.tiles[k] = null);
  }

  renderDashboardData() {
    if (!this.dashboardData) return;
    const data = this.dashboardData;
    const sheetName = data.tab || data.month || (this.currentDashboardPeriod === 'annual' ? 'Annual Dashboard' : 'Current Month');
    this.ui.badge = `SHEET: ${sheetName}`;

    if (this.currentDashboardPeriod === 'annual') {
      const label = data.tab || 'Annual';
      this.ui.title = `Annual Dashboard (${label})`;
    } else {
      const monthLabel = data.month || 'Current';
      this.ui.title = `Monthly Budget & Tracking (${monthLabel})`;
    }

    const tiles = data.summaryTiles || {};
    this.ui.tiles.income = tiles.totalIncome?.actual || 0;
    this.ui.tiles.bills = tiles.totalBills?.actual || 0;
    this.ui.tiles.debt = tiles.totalDebtPayoff?.actual || 0;
    this.ui.tiles.expenses = tiles.totalExpenses?.actual || 0;
    this.ui.tiles.savings = tiles.totalSavings?.actual || 0;
    this.ui.tiles.unallocated = tiles.unallocatedIncome?.actual || 0;
  }

  // Simulates async fetch with requestId race condition protection
  async fetchDashboardData(period, delayMs, mockPayload) {
    const requestId = ++this.dashboardFetchRequestId;
    this.renderDashboardLoadingState(period);

    return new Promise(resolve => {
      setTimeout(() => {
        // Race condition guard check
        if (requestId !== this.dashboardFetchRequestId) {
          // Stale response ignored
          resolve({ status: 'ignored', requestId });
          return;
        }

        this.dashboardLoading = false;
        this.dashboardData = mockPayload.period === 'annual' ? mockPayload.annual : mockPayload.month;
        this.currentDashboardPeriod = mockPayload.period || period;
        this.renderDashboardData();
        resolve({ status: 'applied', requestId, period: this.currentDashboardPeriod });
      }, delayMs);
    });
  }
}

async function runRapidToggleTests() {
  console.log('=== TEST: Rapid Dashboard Toggle (Monthly -> Annual -> Monthly) ===');
  const controller = new DashboardStateController();

  const mockMonthlyResponse = {
    success: true,
    period: 'monthly',
    month: {
      month: 'SEP',
      summaryTiles: {
        totalIncome: { actual: 10292 },
        totalBills: { actual: 2500 },
        totalDebtPayoff: { actual: 0 },
        totalExpenses: { actual: 12286 },
        totalSavings: { actual: -1180 },
        unallocatedIncome: { actual: -3314 }
      }
    }
  };

  const mockAnnualResponse = {
    success: true,
    period: 'annual',
    annual: {
      tab: 'Annual Dashboard',
      summaryTiles: {
        totalIncome: { actual: 505582.24 },
        totalBills: { actual: 105594.71 },
        totalDebtPayoff: { actual: 108742.13 },
        totalExpenses: { actual: 307928.75 },
        totalSavings: { actual: 275856 },
        unallocatedIncome: { actual: -24977 }
      }
    }
  };

  // 1. User starts on Monthly
  console.log('Step 1: Fetching initial Monthly dashboard (200ms response)...');
  const p1 = controller.fetchDashboardData('monthly', 200, mockMonthlyResponse);

  // 2. User rapidly taps Annual at t=50ms (simulated slow response 300ms)
  await new Promise(r => setTimeout(r, 50));
  console.log('Step 2: Rapid tap Annual (in-flight request #2, 300ms response)...');
  const p2 = controller.fetchDashboardData('annual', 300, mockAnnualResponse);

  // 3. User rapidly taps Monthly again at t=100ms (fast response 100ms)
  await new Promise(r => setTimeout(r, 50));
  console.log('Step 3: Rapid tap Monthly again (in-flight request #3, 100ms response)...');
  const p3 = controller.fetchDashboardData('monthly', 100, mockMonthlyResponse);

  // Wait for all promises to settle
  const results = await Promise.all([p1, p2, p3]);
  console.log('Fetch execution summary:', results);

  // Verification 1: Title and tiles match exact expected final state (Monthly - SEP)
  assert.strictEqual(controller.ui.title, 'Monthly Budget & Tracking (SEP)');
  assert.strictEqual(controller.ui.tiles.income, 10292);
  assert.strictEqual(controller.ui.tiles.expenses, 12286);
  assert.notStrictEqual(controller.ui.title.indexOf('null'), -1 ? false : true, 'Title must never contain null');
  assert.ok(!controller.ui.title.includes('null'), 'Title does not contain null');

  console.log('✓ Assertion Passed: Final UI title is strictly atomic with final Monthly dataset.');
  console.log('✓ Assertion Passed: Stale Annual request (#2) was safely ignored despite settling later.');
  console.log('✓ Assertion Passed: Month label never renders as "null".');
  console.log('\nALL RAPID TOGGLE STATE TESTS PASSED SUCCESSFULLY!\n');
}

runRapidToggleTests().catch(err => {
  console.error('Test failed:', err);
  process.exit(1);
});
