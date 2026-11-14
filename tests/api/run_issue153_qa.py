"""Create a NEW isolated QA153 stack and run real API cases. Never reset/reseed.

Requires Docker and a local forecast Python runtime image (override QA153_SEED_IMAGE).
Random QA credentials exist only in child environments, never in artifacts.
"""
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]


def main():
    env=os.environ.copy()
    project=env.get('QA153_PROJECT','greenmart-qa153-20261114')
    port=int(env.get('QA153_PORT','18161'))
    if not re.fullmatch(r'greenmart-qa153-[a-z0-9-]+',project) or not 18000<=port<19000:
        raise SystemExit('Only isolated QA153 names and ports allowed')
    def run(args,data=None,timeout=600):
        p=subprocess.run(args,cwd=ROOT,env=env,input=data,capture_output=True,timeout=timeout)
        if p.returncode:
            raise SystemExit(f'QA153 operation failed (exit {p.returncode}); raw output withheld')
        return p.stdout
    source=run(['git','rev-parse','HEAD']).decode().strip()
    if run(['git','diff','--name-only','HEAD','--','backend','forecast-service/scripts','docker-compose.yml']).strip():
        raise SystemExit('Backend/seed/compose differs from source commit; refusing mislabeled test')
    for command in [['docker','ps','-aq','--filter',f'label=com.docker.compose.project={project}'],
                    ['docker','volume','ls','-q','--filter',f'label=com.docker.compose.project={project}']]:
        if run(command).strip(): raise SystemExit('QA153 exists; refuse reset/reseed. Use a new QA153 project/port.')
    env.update(POSTGRES_DB='greenmart',POSTGRES_USER='greenmart',FORECAST_DB_USER='forecast_ro',RABBITMQ_DEFAULT_USER='greenmart')
    for key in ['POSTGRES_PASSWORD','FORECAST_DB_PASSWORD','REDIS_PASSWORD','RABBITMQ_DEFAULT_PASS',
                'JWT_SECRET','CHANNEL_WEB_SECRET','CHANNEL_MARKET_SECRET','FORECAST_INTERNAL_TOKEN']:
        env[key]=secrets.token_hex(32)
    env.update(QA153_PROJECT=project,QA153_PORT=str(port),QA153_SOURCE_COMMIT=source,
               QA153_REPORT=env.get('QA153_REPORT',str(ROOT/'docs/testing/results/issue-153-2026-11-14.json')))
    if not env.get('GREENMART_DEMO_PASSWORD'):
        raise SystemExit('Set GREENMART_DEMO_PASSWORD to the documented demo password in process environment')
    local=ROOT/'.local-tools'/project
    local.mkdir(parents=True,exist_ok=False)
    override=local/'compose.yml'
    image='greenmart-backend:qa153-'+source[:7]
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
''',encoding='utf-8')
    compose=['docker','compose','--env-file',os.devnull,'-p',project,'-f',str(ROOT/'docker-compose.yml'),'-f',str(override)]
    run(compose+['config','--quiet'])
    print('Building QA153 backend '+source[:7]+'...',flush=True)
    run(['docker','build','--label','org.opencontainers.image.revision='+source,'-t',image,'backend'])
    print('Starting new isolated QA153 dependencies/backend...',flush=True)
    run(compose+['up','-d','--no-build','--wait','--wait-timeout','240','postgres','redis','rabbitmq','backend'])
    data=local/'seed'
    data.mkdir()
    seed_image=env.get('QA153_SEED_IMAGE','greenmart-forecast:qa151')
    user=['--user',f'{os.getuid()}:{os.getgid()}'] if os.name=='posix' else []
    print('Generating official demo seed into fresh QA153 DB...',flush=True)
    run(['docker','run','--rm','--network','none',*user,'--entrypoint','python',
         '--mount',f'type=bind,src={ROOT/"forecast-service/scripts"},dst=/qa/scripts,readonly',
         '--mount',f'type=bind,src={data},dst=/qa/data','-w','/qa',seed_image,'scripts/generate_data.py'])
    run(compose+['exec','-T','postgres','psql','-X','-U','greenmart','-d','greenmart','-v','ON_ERROR_STOP=1'],(data/'seed_data.sql').read_bytes())
    print('Running promotion and expiry QA153 cases; sanitized output only...',flush=True)
    p=subprocess.run([sys.executable,str(Path(__file__).with_name('issue153_edges.py'))],env=env,cwd=ROOT)
    print('QA153 complete; dedicated containers/volumes preserved. Test exit '+str(p.returncode),flush=True)
    return p.returncode


if __name__=='__main__':
    raise SystemExit(main())
