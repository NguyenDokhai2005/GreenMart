// Real Newman against a tiny HTTP double: proves runner pass/fail, NOT backend correctness.
const {createServer} = require('node:http');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const user = {id:7,username:'thungan.gm001',fullName:'Demo',role:'CASHIER',branches:[{id:1,code:'GM-001',name:'Demo'}]};
const order = {id:42,code:'POS-QA149',channel:'POS',branch:{id:1},status:'COMPLETED',total:238000,itemCount:3,
  items:[{productId:900001},{productId:900002}],createdBy:{id:7},createdAt:'2026-11-07T00:00:00Z',subtotal:238000,
  discount:0,itemDiscount:0,orderDiscount:0,manualDiscount:0,
  payments:[{method:'CASH',amount:238000,status:'PAID',cashReceived:250000,change:12000}]};
async function scenario(mode, expected) {
  let sold=false,loggedOut=false;
  const server=createServer((req,res)=>{
    let body={},code=200;
    if(req.url.endsWith('/auth/login')) body={accessToken:'qa-double-token',refreshToken:'qa-double-refresh',tokenType:'Bearer',expiresIn:900,user};
    else if(req.url.endsWith('/auth/me')) body=user;
    else if(req.url.includes('/inventory?')) body={content:[{available:sold?9:10}]};
    else if(req.url.endsWith('/orders/quote')) body={subtotal:238000,itemDiscount:0,orderDiscount:0,total:238000,canComplete:true,lines:[{productId:900001,lineTotal:180000},{productId:900002,lineTotal:58000}]};
    else if(req.url.endsWith('/orders/pos')) {sold=true;body=order;code=201;}
    else if(req.url.endsWith('/orders/42')) {body=order;if(mode==='missing-receipt'){body={code:'NOT_FOUND'};code=404;}}
    else if(req.url.endsWith('/auth/logout')) {code=204;loggedOut=true;}
    else code=404;
    res.writeHead(code,{'Content-Type':'application/json'});res.end(code===204?'':JSON.stringify(body));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'greenmart149-'));
  try {
    const status=await new Promise((resolve,reject)=>{
      const child=spawn(process.execPath,[path.join(__dirname,'run-newman.cjs')],{env:{...process.env,
        QA_BASE_URL:`http://127.0.0.1:${server.address().port}/api/v1`,QA_REPORT_DIR:dir,QA_TEST_MODE:'http-double',
        QA_FORCE_FAILURE:mode==='forced-failure'?'true':'false'},stdio:'pipe'});
      child.on('error',reject);child.on('exit',resolve);
    });
    if(status!==expected) console.log(fs.readFileSync(path.join(dir,'summary.json'),'utf8'));
    assert.equal(status,expected,mode);
    assert.equal(loggedOut,true,'logout still runs after an assertion failure');
    const raw=fs.readFileSync(path.join(dir,'summary.json'),'utf8');
    const report=JSON.parse(raw);
    assert.equal(report.requests,8);assert.equal(report.passed,expected===0);
    for(const token of ['qa-double-token','qa-double-refresh','Demo@123']) assert.ok(!raw.includes(token));
    const junit=fs.readFileSync(path.join(dir,'newman-report.xml'),'utf8');
    assert.equal(junit.includes('<failure'),expected!==0);
    console.log(`PASS ${mode}: exit ${status}, 8 requests, logout and safe artifacts verified`);
  } finally {await new Promise(resolve=>server.close(resolve));}
}
(async()=>{await scenario('success',0);await scenario('forced-failure',1);await scenario('missing-receipt',1);})()
  .catch(e=>{console.error(e.message);process.exitCode=1;});
