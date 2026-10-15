"""Evaluate actual HTTP retrieval. No model answer quality is inferred.

Set ZHICE_EVAL_PASSWORD to a disposable local demo account password.
This imports only the original fictional examples into team-guide.
"""
from pathlib import Path
import argparse
import http.cookiejar
import json
import os
import time
import uuid
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener

parser=argparse.ArgumentParser()
parser.add_argument('--url',default='http://127.0.0.1:8093')
parser.add_argument('--username',default='demo_anna')
parser.add_argument('--space',default='team-guide')
parser.add_argument('--output',default='evaluation/retrieval-report.json')
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
if args.url!='http://127.0.0.1:8093':
    raise SystemExit('This evaluation runner only targets the disposable local port8093 demo')
password=os.environ.get('ZHICE_EVAL_PASSWORD')
if not password:
    raise SystemExit('Set ZHICE_EVAL_PASSWORD; do not store credentials in source')
http=build_opener(ProxyHandler({}),HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf={}
def request(path,method='GET',body=None,content_type=None):
    headers={}
    if csrf and method!='GET':headers[csrf['headerName']]=csrf['token']
    if body is not None and not isinstance(body,bytes):
        body=json.dumps(body,ensure_ascii=False).encode()
        content_type='application/json'
    if content_type:headers['Content-Type']=content_type
    with http.open(Request(args.url+path,data=body,method=method,headers=headers),timeout=120) as res:
        raw=res.read()
        return json.loads(raw) if raw else None
csrf.update(request('/api/session')['csrf'])
request('/api/login','POST',urlencode({'username':args.username,'password':password}).encode(),'application/x-www-form-urlencoded')
csrf.update(request('/api/session')['csrf'])
prefix=f'/api/spaces/{args.space}'
documents=request(prefix+'/documents')['items']
existing={d['filename'] for d in documents}
for path in sorted((root/'examples').glob('*.md')):
    if path.name in existing:continue
    boundary='ZhiceEvaluation'+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: text/markdown\r\n\r\n').encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
    doc=request(prefix+'/documents','POST',body,'multipart/form-data; boundary='+boundary)
    print(json.dumps({'imported':path.name,'chunks':doc['chunkCount']},ensure_ascii=True),flush=True)
dataset=json.loads((root/'evaluation/questions.json').read_text(encoding='utf-8'))
reports={}
for mode in ['bm25','vector','hybrid']:
    rows=[]
    for case in dataset['cases']:
        begin=time.monotonic()
        result=request(prefix+'/search?'+urlencode({'q':case['question'],'mode':mode,'limit':5}))
        sources=result['sources']
        ranks=[]
        if case['file']:
            ranks=[i+1 for i,s in enumerate(sources) if s['filename']==case['file'] and case['gold'] in s['text']]
        rows.append({'id':case['id'],'question':case['question'],'gold_file':case['file'],
                     'first_relevant_rank':min(ranks) if ranks else None,
                     'no_result':not sources,'elapsed_seconds':round(time.monotonic()-begin,3),
                     'sources':sources})
    positives=[r for r in rows if r['gold_file']]
    negatives=[r for r in rows if not r['gold_file']]
    reports[mode]={'recall_at_1':sum(r['first_relevant_rank']==1 for r in positives)/len(positives),
                   'recall_at_3':sum(r['first_relevant_rank'] is not None and r['first_relevant_rank']<=3 for r in positives)/len(positives),
                   'mrr_at_5':sum(1/r['first_relevant_rank'] if r['first_relevant_rank'] else 0 for r in positives)/len(positives),
                   'negative_no_result':sum(r['no_result'] for r in negatives),
                   'negative_total':len(negatives),'cases':rows}
    print(json.dumps({'mode':mode,**{k:v for k,v in reports[mode].items() if k!='cases'}}),flush=True)
report={'dataset':dataset['name'],'scope':dataset['scope'],'answerable':20,'negative':4,'modes':reports,
        'model_configuration':request('/api/session')['models']}
out=Path(args.output)
if not out.is_absolute():out=root/out
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
