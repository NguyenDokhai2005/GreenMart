const fs = require('node:fs');
const path = require('node:path');
class SafeReporter {
  constructor() { this.rows = []; }
  onTestEnd(test, result) {
    const row = { title: test.title, status: result.status, durationMs: result.duration,
      annotations: test.annotations.filter(a => ['blocked', 'qa-evidence', 'qa-stage'].includes(a.type)) };
    if (result.errors.length) row.error = 'Assertion or prerequisite failed; raw errors withheld to protect credentials';
    this.rows.push(row);
    process.stdout.write(`[${result.status}] ${test.title}\n`);
  }
  onEnd(result) {
    const summary = {};
    for (const row of this.rows) summary[row.status] = (summary[row.status] || 0) + 1;
    const report = process.env.QA156_REPORT;
    fs.mkdirSync(path.dirname(report), { recursive: true });
    const blocked = this.rows.some(r => r.annotations.some(a => a.type === 'blocked'));
    const acceptance = result.status !== 'passed' ? 'FAILED' : blocked ? 'BLOCKED' : 'PASS';
    fs.writeFileSync(report, JSON.stringify({ issue: 156, date: new Date().toISOString(),
      sourceCommit: process.env.QA156_SOURCE_COMMIT, project: process.env.QA156_PROJECT,
      api: `http://127.0.0.1:${process.env.QA156_PORT}/api/v1`, ui: `http://127.0.0.1:${process.env.QA156_UI_PORT}`,
      status: result.status, acceptance, summary, tests: this.rows,
      limitations: ['Skipped business UI tests are BLOCKED, never counted as completed POS/online E2E.',
        'API lifecycle is reported separately from browser business interaction.',
        'Traces/video/storage state/raw errors/headers/request bodies are not exported.'] }, null, 2), { flag: 'wx' });
    process.stdout.write(JSON.stringify({ summary, status: result.status }) + '\n');
    if (blocked) return { status: 'failed' };
  }
}
module.exports = SafeReporter;
