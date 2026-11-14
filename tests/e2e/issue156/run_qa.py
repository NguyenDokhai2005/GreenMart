"""Create a NEW isolated QA156 stack and run real API cases. Never reset/reseed.

Requires Docker and a local forecast Python runtime image (override QA156_SEED_IMAGE).
Random QA credentials exist only in child environments, never in artifacts.
"""
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[3]


def main():
    env=os.environ.copy()
    project=env.get('QA156_PROJECT','greenmart-qa156-20261114')
    port=int(env.get('QA156_PORT','18166'))
    report=Path(env.get('QA156_REPORT',str(ROOT/'docs/testing/results/issue-156-2026-11-14.json')))
    if report.exists(): raise SystemExit('Evidence exists; choose a new QA156_REPORT before starting services')
    if not re.fullmatch(r'greenmart-qa156-[a-z0-9-]+',project) or not 18000<=port<19000:
        raise SystemExit('Only isolated QA156 names and ports allowed')
    def run(args,data=None,timeout=600):
        p=subprocess.run(args,cwd=ROOT,env=env,input=data,capture_output=True,timeout=timeout)
        if p.returncode:
            raise SystemExit(f'QA156 operation failed (exit {p.returncode}); raw output withheld')
        return p.stdout
    source=run(['git','rev-parse','HEAD']).decode().strip()
    if run(['git','diff','--name-only','HEAD','--','backend','frontend','forecast-service/scripts','docker-compose.yml']).strip():
        raise SystemExit('Backend/seed/compose differs from source commit; refusing mislabeled test')
    for command in [['docker','ps','-aq','--filter',f'label=com.docker.compose.project={project}'],
                    ['docker','volume','ls','-q','--filter',f'label=com.docker.compose.project={project}']]:
        if run(command).strip(): raise SystemExit('QA156 exists; refuse reset/reseed. Use a new QA156 project/port.')
    env.update(POSTGRES_DB='greenmart',POSTGRES_USER='greenmart',FORECAST_DB_USER='forecast_ro',RABBITMQ_DEFAULT_USER='greenmart')
    for key in ['POSTGRES_PASSWORD','FORECAST_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_DEFAULT_PASS',
                'JWT_SECRET','CHANNEL_WEB_SECRET','CHANNEL_MARKET_SECRET','FORECAST_INTERNAL_TOKEN']:
        env[key]=secrets.token_hex(32)
    env.update(QA156_PROJECT=project,QA156_PORT=str(port),QA156_SOURCE_COMMIT=source,
               QA156_REPORT=env.get('QA156_REPORT',str(ROOT/'docs/testing/results/issue-156-2026-11-14.json')))
    if not env.get('GREENMART_DEMO_PASSWORD'):
        raise SystemExit('Set GREENMART_DEMO_PASSWORD to the documented demo password in process environment')
    local=ROOT/'.local-tools'/project
    local.mkdir(parents=True,exist_ok=False)
    override=local/'compose.yml'
    image='greenmart-backend:qa156-'+source[:7]
    ui_port=int(env.get('QA156_UI_PORT','13166'))
    if not 13000<=ui_port<14000: raise SystemExit('Only isolated frontend ports allowed')
    env['QA156_UI_PORT']=str(ui_port)
    env['QA156_CHANNEL_KEY']=secrets.token_hex(32)
    override.write_text(f'''services:
  backend:
    image: {image}
    env_file: !reset []
    environment:
      POSTGRES_USER: ${{POSTGRES_USER}}
      POSTGRES_PASSWORD: ${{POSTGRES_PASSWORD}}
      REDIS_PASSWORD: ${{REDIS_PASSWORD}}
      RABBITMQ_DEFAULT_USER: ${{RABBITMQ_DEFAULT_USER}}
      RABBITMQ_DEFAULT_PASS: ${{RABBITMQ_DEFAULT_PASS}}
      JWT_SECRET: ${{JWT_SECRET}}
      JAVA_TOOL_OPTIONS: '-Xmx512m -XX:ActiveProcessorCount=2'
    mem_limit: 768m
    cpus: 2
    ports: !override ['127.0.0.1:{port}:8080']
  frontend:
    image: greenmart-frontend:qa156-{source[:7]}
    mem_limit: 192m
    ports: !override ['127.0.0.1:{ui_port}:8080']
''',encoding='utf-8')
    compose=['docker','compose','--env-file',os.devnull,'-p',project,'-f',str(ROOT/'docker-compose.yml'),'-f',str(override)]
    run(compose+['config','--quiet'])
    print('Building QA156 backend '+source[:7]+'...',flush=True)
    run(['docker','build','--label','org.opencontainers.image.revision='+source,'-t',image,'backend'])
    print('Building QA156 frontend...',flush=True)
    run(['docker','build','--label','org.opencontainers.image.revision='+source,'-t','greenmart-frontend:qa156-'+source[:7],'frontend'])
    print('Starting new isolated QA156 backend/frontend/dependencies...',flush=True)
    run(compose+['up','-d','--no-build','--wait','--wait-timeout','240','postgres','redis','rabbitmq','backend','frontend'])
    data=local/'seed'
    data.mkdir()
    seed_image=env.get('QA156_SEED_IMAGE','greenmart-forecast:qa151')
    user=['--user',f'{os.getuid()}:{os.getgid()}'] if os.name=='posix' else []
    print('Generating official demo seed into fresh QA156 DB...',flush=True)
    run(['docker','run','--rm','--network','none',*user,'--entrypoint','python',
         '--mount',f'type=bind,src={ROOT/"forecast-service/scripts"},dst=/qa/scripts,readonly',
         '--mount',f'type=bind,src={data},dst=/qa/data','-w','/qa',seed_image,'scripts/generate_data.py'])
    run(compose+['exec','-T','postgres','psql','-X','-U','greenmart','-d','greenmart','-v','ON_ERROR_STOP=1'],(data/'seed_data.sql').read_bytes())
    print('Running Playwright QA156 with sanitized reporter...',flush=True)
    env['QA156_REPORT']=env.get('QA156_REPORT',str(ROOT/'docs/testing/results/issue-156-2026-11-14.json'))
    import shutil
    npm=shutil.which('npm.cmd' if os.name=='nt' else 'npm')
    p=subprocess.run([npm,'test'],env=env,cwd=ROOT/'tests/e2e/issue156')
    print('QA156 complete; dedicated containers/volumes preserved. Test exit '+str(p.returncode),flush=True)
    return p.returncode


if __name__=='__main__':
    raise SystemExit(main())
