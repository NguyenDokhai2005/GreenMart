import http from 'k6/http';
import { sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import crypto from 'k6/crypto';
import exec from 'k6/execution';

const base = __ENV.QA_BASE_URL;
const pid = Number(__ENV.LOAD_PRODUCT_ID);
const sku = __ENV.LOAD_SKU;
if (!/^http:\/\/host\.docker\.internal:18\d{3}\/api\/v1$/.test(base ?? '') || !pid || !sku) throw new Error('Isolated QA parameters required');
const errors = new Rate('business_errors');
const reads = new Trend('read_duration_ms', true);
const creates = new Trend('create_duration_ms', true);
const orders = new Counter('created_orders');
const flows = new Counter('measured_flows');
const logicalErrors = new Counter('logical_failures');
let session;
export const options = {
  scenarios: {
    warmup: { executor:'constant-vus', vus:5, duration:'30s', gracefulStop:'15s' },
    load50: { executor:'constant-vus', vus:50, duration:'5m', startTime:'45s', gracefulStop:'30s' },
  },
  thresholds: { business_errors:['rate<0.01'], logical_failures:['count==0'], read_duration_ms:['p(95)<300'], create_duration_ms:['p(95)<500'], created_orders:['count>0'], measured_flows:['count>0'] },
  summaryTrendStats:['min','med','max','p(95)'],
};
function parse(r) { try { return r.json(); } catch { return null; } }
function call(method,path,body,kind,expected=200,key=null) {
  const headers={'Content-Type':'application/json',...(session?{Authorization:'Bearer '+session.accessToken}:{})};
  if(key)headers['Idempotency-Key']=key;
  const r=http.request(method,base+path,body===null?null:JSON.stringify(body),{headers,timeout:'30s',redirects:0,tags:{endpoint:kind}});
  if(exec.scenario.name==='load50') {
    errors.add(r.status!==expected);
    if(kind==='read')reads.add(r.timings.duration);
    if(kind==='create')creates.add(r.timings.duration);
  }
  return r;
}
export function setup() {
  logicalErrors.add(0);
  const sessions=[];
  for(let i=0;i<55;i++) {
    const r=http.post(base+'/auth/login',JSON.stringify({username:'thungan.gm001',password:__ENV.GREENMART_DEMO_PASSWORD}),{headers:{'Content-Type':'application/json'},timeout:'30s'});
    const data=parse(r);
    if(r.status!==200||!data?.accessToken)throw new Error('Dedicated QA setup login failed; credentials withheld');
    sessions.push(data);
  }
  return {sessions};
}
export default function(data) {
  session=data.sessions[(exec.vu.idInTest-1)%data.sessions.length];
  call('GET','/products/lookup?code='+encodeURIComponent(sku)+'&branchId=1',null,'read');
  const body={branchId:1,items:[{productId:pid,quantity:1}]};
  const q=call('POST','/orders/quote',body,'quote');
  const quote=parse(q);
  if(q.status!==200||typeof quote?.total!=='number'){sleep(1);return;}
  const key=crypto.randomBytes(16);
  const hex=Array.from(new Uint8Array(key),x=>x.toString(16).padStart(2,'0')).join('');
  const id=`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  const r=call('POST','/orders/pos',{...body,expectedTotal:quote.total,payment:{method:'CASH',cashReceived:quote.total}},'create',201,id);
  const order=parse(r);
  if(r.status===201&&order?.status==='COMPLETED') {
    if(exec.scenario.name==='load50')orders.add(1);
    call('GET','/orders/'+order.id,null,'read');
  } else if(exec.scenario.name==='load50' && r.status===201) logicalErrors.add(1);
  call('GET','/inventory?branchId=1&productId='+pid,null,'read');
  if(exec.scenario.name==='load50')flows.add(1);
  sleep(1);
}
export function teardown(data) {
  for(const s of data.sessions)http.post(base+'/auth/logout',JSON.stringify({refreshToken:s.refreshToken}),{headers:{'Content-Type':'application/json',Authorization:'Bearer '+s.accessToken},timeout:'30s'});
}
export function handleSummary(data) {
  const count=n=>data.metrics[n]?.values?.count??0;
  const result={sourceCommit:__ENV.SOURCE_COMMIT,issue:159,workload:{warmupVus:5,warmupSeconds:30,steadyVus:50,steadySeconds:300,pacingSeconds:1,flow:'lookup -> quote -> POS CASH -> detail -> inventory',account:'QA CASHIER, per-VU login',hardware:'Docker backend 768MiB/JVM512MiB/2CPU'},
    iterations:count('iterations'),measuredFlows:count('measured_flows'),createdOrders:count('created_orders'),
    businessErrorRate:data.metrics.business_errors?.values?.rate??null,logicalFailures:count('logical_failures'),
    readDurationMs:data.metrics.read_duration_ms?.values??{},createDurationMs:data.metrics.create_duration_ms?.values??{},
    thresholds:Object.fromEntries(Object.entries(data.metrics).filter(([,m])=>m.thresholds).map(([n,m])=>[n,Object.fromEntries(Object.entries(m.thresholds).map(([t,v])=>[t,v.ok]))]))};
  return {'/results/load50-summary.json':JSON.stringify(result,null,2)+'\n',stdout:JSON.stringify({measuredFlows:result.measuredFlows,createdOrders:result.createdOrders,businessErrorRate:result.businessErrorRate})+'\n'};
}
