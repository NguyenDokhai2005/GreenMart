// HTTP doubles verify failure reporting/cleanup only, never backend correctness.
const {createServer}=require('node:http');
const {spawn}=require('node:child_process');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const assert=require('node:assert/strict');
const statuses=[401,200,200,200,401,403,403,403,401,200,200,204,401,204];
const error=code=>({code,message:'Denied',traceId:'qa242-double-trace'});
const cashier={id:7,role:'CASHIER',branches:[{id:1}]};
const owner={id:8,role:'OWNER',branches:[{id:1},{id:2},{id:3}]};
const session=(user,suffix)=>({accessToken:'qa242-access-'+suffix,refreshToken:'qa242-refresh-'+suffix,
  tokenType:'Bearer',expiresIn:900,user});
const bodies=[error('UNAUTHORIZED'),session(cashier,'old'),cashier,session(cashier,'new'),
  error('INVALID_REFRESH_TOKEN'),error('BRANCH_FORBIDDEN'),error('FORBIDDEN'),error('FORBIDDEN'),
  error('INVALID_REFRESH_TOKEN'),session(owner,'owner'),{content:[{branch:{id:3}}]},null,error('INVALID_REFRESH_TOKEN'),null];
async function scenario(mode){
  let index=0;
  const server=createServer((req,res)=>{
    const i=index++;
    if(mode==='request-error' && i===0){req.socket.destroy();return;}
    const status=mode==='wrong-branch-allowed' && i===5?200:statuses[i];
    res.writeHead(status||500,{'Content-Type':'application/json'});
    res.end(status===204?'':JSON.stringify(bodies[i]||{}));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'greenmart242-'));
  try{
    const exit=await new Promise((resolve,reject)=>{
      const child=spawn(process.execPath,[path.join(__dirname,'run-newman.cjs')],{
        env:{...process.env,QA_BASE_URL:`http://127.0.0.1:${server.address().port}/api/v1`,QA_REPORT_DIR:dir},stdio:'pipe'});
      child.on('error',reject);child.on('exit',resolve);
    });
    const raw=fs.readFileSync(path.join(dir,'auth242/summary.json'),'utf8');
    const report=JSON.parse(raw);
    assert.equal(exit,mode==='success'?0:1);
    assert.equal(report.passed,mode==='success');
    assert.equal(index,14,'collection continues to cleanup after failure');
    assert.equal(report.requests,14);
    assert.equal(report.cases.at(-1).http,204);
    for(const secret of ['qa242-access-old','qa242-access-new','qa242-refresh-old','qa242-refresh-new','qa242-refresh-owner','Demo@123'])
      assert.ok(!raw.includes(secret),'safe artifacts');
    if(mode==='request-error')assert.ok(report.requestErrors.length>0);
    console.log(`PASS AUTH242 ${mode}: exit ${exit}, cleanup and sanitized artifacts`);
  }finally{await new Promise(resolve=>server.close(resolve));}
}
(async()=>{for(const mode of ['success','wrong-branch-allowed','request-error'])await scenario(mode);})()
  .catch(()=>{console.error('AUTH242 runner regression failed; raw details withheld');process.exitCode=1;});
