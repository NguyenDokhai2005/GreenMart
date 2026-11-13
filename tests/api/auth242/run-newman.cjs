// Use the same pinned Newman dependency as QA149; never export raw executions.
const fs = require('node:fs');
const path = require('node:path');
const newman = require('../pos149/node_modules/newman');
const collection = require('./auth.postman_collection.json');
const output = path.resolve(process.env.QA_REPORT_DIR || 'reports', 'auth242');
collection.variable = [
  {key:'baseUrl',value:process.env.QA_BASE_URL || 'http://127.0.0.1:18081/api/v1'},
  // Public seed demo credential; never read .env or persist runtime tokens.
  {key:'demoPassword',value:process.env.GREENMART_DEMO_PASSWORD || 'Demo@123'}
];
fs.mkdirSync(output,{recursive:true});
newman.run({collection,reporters:[],timeoutRequest:15000,timeoutScript:5000,
  timeout:180000,ignoreRedirects:true},(error,summary)=>{
  const executions=summary?.run?.executions || [];
  const cases=executions.flatMap(e=>(e.assertions || []).map(a=>({
    request:e.item.name,assertion:a.assertion,pass:!a.error,http:e.response?.code || null
  })));
  const failures=summary?.run?.failures?.length || 0;
  const requestErrors=executions.filter(e=>e.requestError).map(e=>({
    request:e.item.name,code:e.requestError.code || 'REQUEST_ERROR'}));
  const passed=!error && !failures && !requestErrors.length && cases.length>0
    && executions.length===collection.item.length;
  const report={issue:242,timestamp:new Date().toISOString(),
    sourceCommit:process.env.QA_SOURCE_COMMIT || 'not-recorded',
    requests:executions.length,assertions:cases.length,failures,requestErrors,
    runnerError:Boolean(error),passed,cases};
  fs.writeFileSync(path.join(output,'summary.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({issue:242,requests:report.requests,assertions:cases.length,failures,passed}));
  process.exitCode=passed?0:1;
});
