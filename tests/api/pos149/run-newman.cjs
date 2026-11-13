// Newman artifacts deliberately exclude request/response bodies, headers and tokens.
const fs = require('node:fs');
const path = require('node:path');
const newman = require('newman');
const collection = require('./pos.postman_collection.json');
const output = path.resolve(process.env.QA_REPORT_DIR || 'reports');
const baseUrl = process.env.QA_BASE_URL || 'http://127.0.0.1:18081/api/v1';
collection.variable = [{key:'baseUrl',value:baseUrl},
  {key:'forceFailure',value:process.env.QA_FORCE_FAILURE || 'false'}];
const xml = s => String(s).replace(/[<>&"']/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;','"':'&quot;',"'":'&apos;'}[c]));
fs.mkdirSync(output, {recursive:true});
newman.run({collection, reporters: [], timeoutRequest:10000, timeoutScript:5000, timeout:120000,
  ignoreRedirects:true}, (error, summary) => {
  const executions = summary?.run?.executions || [];
  const cases = executions.flatMap(e => (e.assertions || []).map(a => ({
    request:e.item.name, assertion:a.assertion, pass:!a.error, http:e.response?.code || null})));
  const failures = summary?.run?.failures?.length || 0;
  const failed = Boolean(error || failures || cases.length === 0 || executions.length !== collection.item.length);
  const report = {issue:149, timestamp:new Date().toISOString(), sourceCommit:process.env.QA_SOURCE_COMMIT || 'not-recorded',
    mode:process.env.QA_TEST_MODE || 'real-api', requests:executions.length,
    requestErrors:executions.filter(e=>e.requestError).map(e=>({request:e.item.name,code:e.requestError.code || 'REQUEST_ERROR'})),
    assertions:cases.length, failures, runnerError:Boolean(error), passed:!failed, cases};
  fs.writeFileSync(path.join(output,'summary.json'),JSON.stringify(report,null,2)+'\n');
  const tests = cases.map(c=>`<testcase classname="${xml(c.request)}" name="${xml(c.assertion)}">${c.pass?'':'<failure message="Assertion failed; inspect the contract and HTTP status"/>'}</testcase>`);
  if (failed && !cases.some(c=>!c.pass)) tests.push('<testcase name="runner"><failure message="Runner, script, request or incomplete collection failure"/></testcase>');
  fs.writeFileSync(path.join(output,'newman-report.xml'),`<?xml version="1.0" encoding="UTF-8"?><testsuite name="POS #149" tests="${tests.length}" failures="${tests.filter(t=>t.includes('<failure')).length}">${tests.join('')}</testsuite>\n`);
  console.log(JSON.stringify({requests:report.requests,assertions:report.assertions,failures,passed:!failed,output}));
  process.exitCode = failed ? 1 : 0;
});
