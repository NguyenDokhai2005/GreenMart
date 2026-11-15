import { defineConfig } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';

const project = process.env.QA156_PROJECT;
const revision = process.env.QA156_SOURCE_COMMIT;
const port = Number(process.env.QA156_UI_PORT);
const apiPort = Number(process.env.QA156_PORT);
const report = process.env.QA156_REPORT;
if (!/^greenmart-qa156-[a-z0-9-]+$/.test(project ?? '') || !/^[a-f0-9]{40}$/.test(revision ?? '') ||
    ![port, apiPort].every(p => Number.isInteger(p) && p >= 10000 && p < 19000) || !report) {
  throw new Error('Only explicitly configured isolated QA156 targets allowed');
}
if (fs.existsSync(report)) throw new Error('Evidence exists; select a new QA156_REPORT');
export default defineConfig({
  testDir: '.', testMatch: '*.spec.js', fullyParallel: false, workers: 1, retries: 0,
  timeout: 60000, expect: { timeout: 10000 },
  reporter: [['./safe-reporter.cjs']],
  outputDir: path.resolve('../../../.local-tools', project, 'playwright-output'),
  use: { baseURL: `http://127.0.0.1:${port}`, headless: true,
    trace: 'off', video: 'off', screenshot: 'off',
    launchOptions: process.env.QA156_CHROMIUM_EXE ? { executablePath: process.env.QA156_CHROMIUM_EXE } : {} },
});
