const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const Reporter = require('./safe-reporter.cjs');

function importConfig(env) {
  return spawnSync(process.execPath, ['--input-type=module', '-e', "import('./playwright.config.js')"], {
    cwd: __dirname, env: { ...process.env, ...env }, encoding: 'utf8', windowsHide: true,
  });
}
test('configuration rejects shared project before browser/API execution', () => {
  const result = importConfig({ QA156_PROJECT: 'greenmart', QA156_SOURCE_COMMIT: 'a'.repeat(40),
    QA156_PORT: '18166', QA156_UI_PORT: '13166', QA156_REPORT: path.join(os.tmpdir(), 'qa156-unused.json') });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /isolated QA156/);
});
test('configuration refuses to overwrite existing evidence', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qa156-'));
  const file = path.join(dir, 'report.json');
  fs.writeFileSync(file, 'original');
  const result = importConfig({ QA156_PROJECT: 'greenmart-qa156-guard', QA156_SOURCE_COMMIT: 'a'.repeat(40),
    QA156_PORT: '18166', QA156_UI_PORT: '13166', QA156_REPORT: file });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Evidence exists/);
  assert.equal(fs.readFileSync(file, 'utf8'), 'original');
});
test('reporter withholds raw errors and unapproved annotations', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qa156-report-'));
  process.env.QA156_REPORT = path.join(dir, 'report.json');
  const reporter = new Reporter();
  reporter.onTestEnd({ title: 'safe controlled title', annotations: [{ type: 'untrusted', description: 'secret-canary' }] },
    { status: 'failed', duration: 1, errors: [{ message: 'secret-canary' }] });
  reporter.onEnd({ status: 'failed' });
  const data = fs.readFileSync(process.env.QA156_REPORT, 'utf8');
  assert(!data.includes('secret-canary'));
  assert.equal(JSON.parse(data).acceptance, 'FAILED');
});
test('blocked UI cannot make acceptance or process outcome green', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qa156-blocked-'));
  process.env.QA156_REPORT = path.join(dir, 'report.json');
  const reporter = new Reporter();
  reporter.onTestEnd({ title: 'business UI', annotations: [{ type: 'blocked', description: 'Missing implementation' }] },
    { status: 'skipped', duration: 0, errors: [] });
  const outcome = reporter.onEnd({ status: 'passed' });
  assert.equal(outcome.status, 'failed');
  assert.equal(JSON.parse(fs.readFileSync(process.env.QA156_REPORT, 'utf8')).acceptance, 'BLOCKED');
});
