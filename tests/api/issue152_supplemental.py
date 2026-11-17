"""Supplement #152 with new read routes, barcode audit, and role-price checks.

Reuse HTTP/audit assertions from issue152_rbac; no seeding/reset or service control.
Requires explicit QA152_SOURCE_COMMIT/PROJECT/PORT and a new QA152_REPORT.
"""
from collections import Counter
from datetime import datetime, timezone
import json
import os
import subprocess
import uuid
from urllib.parse import quote

if not os.environ.get('QA152_SOURCE_COMMIT'):
    raise SystemExit('Explicit QA source revision is required')

import issue152_rbac as qa


def main():
    tag, image = subprocess.check_output(['docker','inspect',qa.PROJECT+'-backend-1',
        '--format','{{.Config.Image}}|{{.Image}}'],text=True,timeout=15).strip().split('|')
    revision = subprocess.check_output(['docker','image','inspect',image,'--format',
        '{{index .Config.Labels "org.opencontainers.image.revision"}}'],text=True,timeout=15).strip()
    if tag != 'greenmart-backend:qa152-'+qa.SOURCE[:7] or revision != qa.SOURCE:
        raise RuntimeError('Supplemental backend version mismatch')
    qa.RUNTIME.update(imageTag=tag,imageId=image,sourceRevision=revision)
    product = qa.sql("SELECT row_to_json(t) FROM (SELECT * FROM product WHERE barcode IS NOT NULL ORDER BY id LIMIT 1) t;")
    pid, barcode = product['id'], product['barcode']
    accounts=[('admin','ADMIN',None),('owner','OWNER',None),('owner.pho','OWNER',None)]
    accounts += [(f'{p}.gm00{b}',role,b) for p,role in
        [('quanly','BRANCH_MANAGER'),('thungan','CASHIER'),('thukho','STOREKEEPER')] for b in range(1,4)]
    before = qa.sql("SELECT json_build_object('products',(SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY id)) FROM product t),'categories',(SELECT count(*) FROM category));")
    for name,role,assigned in accounts:
        print('Supplemental read/write matrix for '+name+'...',flush=True)
        session=qa.check(name,'Login supplemental','POST','/auth/login',200,
                         body={'username':name,'password':qa.PASSWORD})
        if not session or 'accessToken' not in session:
            raise RuntimeError('Supplemental login failed')
        qa.SESSIONS.append((name,session))
        for branch in range(1,4):
            allowed=assigned is None or assigned==branch
            status,code=(200,None) if allowed else (403,'BRANCH_FORBIDDEN')
            qa.check(name,'Single inventory route scope','GET',f'/inventory/{branch}/{pid}',status,session,
                     code=code,verify=(lambda d,b=branch:{'scope':d['branch']['id']==b}) if allowed else None)
            qa.check(name,'Barcode lookup branch and audit','GET',f'/products/lookup?code={quote(barcode)}&branchId={branch}',status,
                     session,code=code,verify=(lambda d,b=branch:{'scope':d['branchId']==b}) if allowed else None)
            doc_role=role in ['OWNER','BRANCH_MANAGER','STOREKEEPER']
            qa.check(name,'Stock document list role/branch','GET',f'/stock-documents?branchId={branch}&size=100',
                     status if doc_role else 403,session,code=code if doc_role else 'FORBIDDEN')
        qa.check(name,'Product price privacy','GET',f'/products/{pid}',200,session,
            verify=lambda d,visible=role in ['ADMIN','OWNER','BRANCH_MANAGER']:{
                'costPrice':type(d.get('costPrice')) is int if visible else d.get('costPrice') is None})
        if role!='OWNER':
            body=dict(sku=product['sku'],barcode=barcode,name=product['name'],unit=product['unit'],
                      categoryId=product['category_id'],salePrice=product['sale_price']+1,
                      costPrice=product['cost_price'],active=product['is_active'])
            qa.check(name,'Unauthorized product price change','PUT',f'/products/{pid}',403,session,body,code='FORBIDDEN')
            qa.check(name,'Unauthorized category creation','POST','/categories',403,session,
                     {'name':'QA152 denied '+uuid.uuid4().hex[:8]},code='FORBIDDEN')
        if role in ['OWNER','BRANCH_MANAGER','STOREKEEPER']:
            qa.check(name,'Default stock document list scope','GET','/stock-documents?size=100',200,session,
                verify=lambda d,a=assigned:{'scope':all(a is None or a in
                    [x['branch']['id'],(x.get('toBranch') or {}).get('id')] for x in d['content'])})
    after=qa.sql("SELECT json_build_object('products',(SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY id)) FROM product t),'categories',(SELECT count(*) FROM category));")
    qa.RESULTS.append(dict(id=f'RBAC152-{len(qa.RESULTS)+1:03}',name='Denied catalog writes unchanged',
        assertions={'unchanged':before==after},result='PASS' if before==after else 'FAIL',before=before,after=after))


if __name__=='__main__':
    blocked=None
    try:
        main()
    except Exception:
        blocked='Supplemental execution interrupted; raw output withheld'
    finally:
        for name,session in qa.SESSIONS:
            try:
                qa.check(name,'Supplemental logout','POST','/auth/logout',204,session,{'refreshToken':session['refreshToken']})
            except Exception:
                blocked='Supplemental logout interrupted'
        qa.OUT.parent.mkdir(parents=True,exist_ok=True)
        qa.OUT.write_text(json.dumps(dict(issue=152,date=datetime.now(timezone.utc).isoformat(),sourceCommit=qa.SOURCE,
            project=qa.PROJECT,base=qa.BASE,runtime=qa.RUNTIME,summary=dict(Counter(x['result'] for x in qa.RESULTS)),
            blocked=blocked,checks=qa.RESULTS),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(dict(summary=dict(Counter(x['result'] for x in qa.RESULTS)),blocked=blocked)))
    raise SystemExit(1 if blocked or any(x['result']=='FAIL' for x in qa.RESULTS) else 0)
