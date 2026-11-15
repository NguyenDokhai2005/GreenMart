"""#150: isolated real backend + k6 race, with independent persisted DB checks.

No dependency installation on the host, no source changes, no automatic reset.
Each prepared QA150 dataset can be consumed only once. Keep DB and evidence.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[3]
PROJECT = 'greenmart-qa150'
K6_IMAGE = 'grafana/k6:1.3.0'


def call(command, *, step, **kwargs):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=600, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{step}: exit {result.returncode}; raw output withheld')
    return result.stdout


def host_user():
    return ['--user', f'{os.getuid()}:{os.getgid()}'] if os.name == 'posix' else []


def postgres():
    ids = call(['docker','ps','-q','--filter',f'label=com.docker.compose.project={PROJECT}',
                '--filter','label=com.docker.compose.service=postgres'], step='find QA150 DB').decode().split()
    if len(ids) != 1:
        raise RuntimeError('Expected exactly one running QA150 postgres; do not query a different project')
    return ['docker','exec','-i',ids[0],'psql','-X','-U','greenmart','-d','greenmart','-v','ON_ERROR_STOP=1','-q','-t','-A']


def snapshot():
    sql = """BEGIN READ ONLY;
    SELECT json_build_object(
      'onHand',i.on_hand,'reserved',i.reserved,'available',i.on_hand-i.reserved,
      'orders',(SELECT count(DISTINCT o.id) FROM sales_order o JOIN order_item x ON x.order_id=o.id WHERE x.product_id=900150 AND o.status='COMPLETED'),
      'quantity',(SELECT coalesce(sum(quantity),0) FROM order_item WHERE product_id=900150),
      'payments',(SELECT count(*) FROM payment p WHERE p.status='PAID' AND p.amount=10000 AND EXISTS(SELECT 1 FROM order_item x WHERE x.order_id=p.order_id AND x.product_id=900150)),
      'ledgerRows',(SELECT count(*) FROM inventory_transaction WHERE product_id=900150 AND type='SALE'),
      'ledgerDelta',(SELECT coalesce(sum(delta_on_hand),0) FROM inventory_transaction WHERE product_id=900150 AND type='SALE'),
      'negativeBalances',(SELECT count(*) FROM inventory_transaction WHERE product_id=900150 AND (on_hand_after<0 OR reserved_after<0 OR reserved_after>on_hand_after)))
    FROM inventory i WHERE i.branch_id=1 AND i.product_id=900150;
    ROLLBACK;"""
    return json.loads(call(postgres(), step='read QA150 invariants', input=sql.encode()))


def evaluate(k6, db, exit_code):
    expected = {'attempts':100,'created':5,'insufficientStock':95,'unexpected':0,'iterations':100,
                'finalAvailable':0,'checksRate':1}
    passed = exit_code == 0 and all(k6.get(k) == v for k,v in expected.items())
    passed = passed and k6.get('scenario') == {'executor':'per-vu-iterations','vus':100,'iterationsPerVu':1,'synchronizedStart':True}
    passed = passed and isinstance(k6.get('launchOffsetMaxMs'), (int,float)) and 0 <= k6['launchOffsetMaxMs'] < 1000
    thresholds = [ok for rules in k6.get('thresholds',{}).values() for ok in rules.values()]
    passed = passed and bool(thresholds) and all(ok is True for ok in thresholds)
    expected_db = {'onHand':0,'reserved':0,'available':0,'orders':5,'quantity':5,
                   'payments':5,'ledgerRows':5,'ledgerDelta':-5,'negativeBalances':0}
    return bool(passed and all(db.get(k) == v for k,v in expected_db.items()))


def prepare():
    existing = call(['docker','ps','-aq','--filter',f'label=com.docker.compose.project={PROJECT}'],step='check QA150 containers')
    volumes = call(['docker','volume','ls','-q','--filter',f'label=com.docker.compose.project={PROJECT}'],step='check QA150 volumes')
    if existing.strip() or volumes.strip():
        raise RuntimeError('QA150 exists: refusing to seed/reset. Preserve it and inspect before any new preparation.')
    data = ROOT / '.local-tools/stock150-data'
    data.mkdir(parents=True,exist_ok=False)
    env = os.environ.copy()
    env.update(POSTGRES_DB='greenmart',POSTGRES_USER='greenmart',FORECAST_DB_USER='forecast_ro',RABBITMQ_DEFAULT_USER='greenmart')
    for key in ['POSTGRES_PASSWORD','FORECAST_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_DEFAULT_PASS','JWT_SECRET','CHANNEL_WEB_SECRET','CHANNEL_MARKET_SECRET','FORECAST_INTERNAL_TOKEN']:
        env[key] = secrets.token_hex(32)
    compose = ['docker','compose','-p',PROJECT,'-f',str(ROOT/'docker-compose.yml'),'-f',str(Path(__file__).with_name('compose.qa.yml'))]
    print('Build QA150 backend/seed runtime and obtain pinned k6...',flush=True)
    call(compose+['config','--quiet'],step='validate Compose',env=env)
    call(compose+['build','backend'],step='build backend',env=env)
    call(['docker','build','-t','greenmart-forecast:qa150','forecast-service'],step='build seed runtime')
    call(['docker','pull',K6_IMAGE],step='obtain k6')
    print('Start separate QA150 services on port 18082...',flush=True)
    call(compose+['up','-d','--wait','--wait-timeout','300','postgres','redis','rabbitmq','backend'],step='start QA150',env=env)
    print('Generate SV06 seed and load isolated fixture (5 units)...',flush=True)
    call(['docker','run','--rm','--network','none',*host_user(),'--entrypoint','python',
          '--mount',f'type=bind,src={ROOT / "forecast-service/scripts"},dst=/qa/scripts,readonly',
          '--mount',f'type=bind,src={data},dst=/qa/data','-w','/qa','greenmart-forecast:qa150','scripts/generate_data.py'],step='generate seed')
    call(postgres(),step='load SV06 seed',input=(data/'seed_data.sql').read_bytes())
    call(postgres(),step='load QA150 fixture',input=Path(__file__).with_name('fixture.sql').read_bytes())
    print('QA150 ready; no other stack or database modified',flush=True)


def run(output):
    if output.exists():
        raise RuntimeError('Report exists: refusing to overwrite evidence')
    before = snapshot()
    if any(before.get(k) != v for k,v in {'onHand':5,'reserved':0,'available':5,'orders':0,'quantity':0,'payments':0,'ledgerRows':0}.items()):
        raise RuntimeError('Fixture is not fresh (5 available, no prior sales); refusing reset/reuse')
    source = call(['git','rev-parse','HEAD'],step='record source commit').decode().strip()
    work = ROOT / '.local-tools/stock150-runs' / str(uuid.uuid4())
    work.mkdir(parents=True)
    result = subprocess.run(['docker','run','--rm','--network',f'{PROJECT}_greenmart',*host_user(),
        '--mount',f'type=bind,src={ROOT / "tests/load"},dst=/scripts,readonly',
        '--mount',f'type=bind,src={work},dst=/results',K6_IMAGE,'run','--quiet',
        '-e','BASE_URL=http://backend:8080/api/v1','-e',f'SOURCE_COMMIT={source}',
        '-e','SUMMARY_PATH=/results/k6-summary.json','/scripts/concurrency-stock.js'],
        cwd=ROOT,capture_output=True,timeout=150)
    summary = json.loads((work/'k6-summary.json').read_text()) if (work/'k6-summary.json').exists() else {}
    after = snapshot()
    version = call(['docker','run','--rm','--network','none',K6_IMAGE,'version'],step='record k6 version').decode().strip()
    passed = evaluate(summary,after,result.returncode)
    report = {'issue':150,'executedAt':datetime.now(timezone(timedelta(hours=7))).isoformat(),
              'sourceCommit':source,'project':PROJECT,'k6Version':version,'k6ExitCode':result.returncode,
              'passed':passed,'before':before,'k6':summary,'after':after}
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8') as target:
        json.dump(report,target,ensure_ascii=False,indent=2);target.write('\n')
    print(json.dumps({'passed':passed,'created':summary.get('created'),'insufficientStock':summary.get('insufficientStock'),
                      'k6ExitCode':result.returncode,'after':after,'report':str(output)}))
    return 0 if passed else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--prepare',action='store_true');action.add_argument('--run',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'docs/testing/results/issue-150-2026-11-08.json')
    args = parser.parse_args()
    try:
        if args.prepare: prepare()
        else: raise SystemExit(run(args.output))
    except (RuntimeError,OSError,ValueError,subprocess.TimeoutExpired) as error:
        # Never print raw subprocess output or environment values.
        print(f'QA150 blocked: {type(error).__name__}: {error}' if isinstance(error,RuntimeError)
              else f'QA150 blocked: {type(error).__name__}; raw details withheld')
        raise SystemExit(2)
