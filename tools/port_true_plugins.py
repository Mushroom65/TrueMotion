from pathlib import Path
import zipfile,re,json,sys
import skia
import argparse
ap=argparse.ArgumentParser();ap.add_argument("bundles",type=Path);args=ap.parse_args()
r=Path(__file__).resolve().parents[1]/'app/src/main/assets/effects';report=[]
for pkg in args.bundles.glob('*.tmplugin'):
 with zipfile.ZipFile(pkg) as z:
  m=json.loads(z.read('plugin.json'))
  for path in m.get('effects',[]):
   obj=json.loads(z.read(path));root=obj['node'];child={x['tag']:x for x in root.get('children',[])};params=[];unsupported=[]
   for p in child['params']['children']:
    a=p.get('attrs',{});tag=p['tag']
    if tag=='texture':continue
    if tag!='spinner':unsupported.append(tag);continue
    params.append(dict(id=a['id'],label=a.get('label',a['id']),type='float',default=float(a['default']),min=float(a['min']),max=float(a['max']),step=float(a.get('step',.01))))
   src=z.read(str(Path(path).parent/child['shader']['source']).replace('\\','/')).decode()
   src=re.sub(r'\bsize\b','tmLocalSize',src)
   src=re.sub(r'\bpos\b','scanPosition',src)
   src=src.replace('acLayerSize','layer')
   src=src.replace('(vec4(uv,0.0,1.0)*acLayerToScreen).xy','((fromLayer * float3(uv * layer,1.0)).xy / size)')
   src=re.sub(r'texture2DCv\(inputImg\.texture,([A-Za-z0-9_]+)\)',r'float4(image.eval(\1 * size))',src)
   src=src.replace('acScreenNorm','(coord / size)').replace('acLayerNorm','((toLayer * float3(coord,1.0)).xy / layer)').replace('acTime','time')
   src=re.sub(r'\bvec([234])\b',r'float\1',src);src=re.sub(r'\bmat([234])\b',r'float\1x\1',src)
   src=src.replace('void main()','half4 main(float2 coord)').replace('gl_FragColor=','return ')
   header='uniform shader image;\nuniform float2 size;\nuniform float2 layer;\nuniform float3x3 toLayer;\nuniform float3x3 fromLayer;\nuniform float time;\n'+''.join('uniform float '+p['id']+';\n' for p in params)
   src=src.replace(';return;',';').replace('; return;',';')
   for p in params:
    if p['id']=='length':
     src=re.sub(r'\blength\b(?!\s*\()', 'lengthPx',src);header=header.replace('float length;', 'float lengthPx;');p['id']='lengthPx'
   src=header+src
   try:
    skia.RuntimeEffect.MakeForShader(src)
    if unsupported:raise ValueError(unsupported)
    print(obj['id'],'OK')
    report.append(dict(id=obj['id'],plugin=m['id'],status='shader-compiled'))
   except Exception as e:print(obj['id'],str(e)[:400]);report.append(dict(id=obj['id'],status='pending',reason=str(e)));continue
   strings={l['attrs']['lang']:{s['attrs']['name']:s.get('text','') for s in l.get('children',[])} for l in child.get('strings',{}).get('children',[])}
   def label(s,lang='en'):return strings.get(lang,{}).get(s.removeprefix('@string/'),s)
   for p in params:p['label']=label(p['label'])
   key='true-plugin-'+obj['id'].split('.',1)[-1].replace('.','-')
   out=dict(id=key,runtimeId=obj['id'],plugin=m['id'],name=label(root['attrs']['name']),category='true',description=root['attrs'].get('desc',''),params=params,sksl=src)
   (r/(key+'.json')).write_text(json.dumps(out,ensure_ascii=False,indent=1),encoding='utf8')
   index=json.loads((r/'index.json').read_text());index=list(dict.fromkeys(index+[key]));(r/'index.json').write_text(json.dumps(index,indent=1))
   for lang in ['ru','zh-CN']:
    p=r/'i18n'/f'{lang}.json';i18n=json.loads(p.read_text(encoding='utf8')) if p.exists() else {'effects':{},'categories':{}}
    i18n['effects'][key]={'name':label(root['attrs']['name'],lang[:2]),'description':root['attrs'].get('desc',''),'params':{p['id']:{'label':label(c['attrs'].get('label',p['id']),lang[:2])} for p,c in zip(params,[x for x in child['params']['children'] if x['tag']=='spinner'])}};p.write_text(json.dumps(i18n,ensure_ascii=False,indent=1),encoding='utf8')
p=Path(__file__).resolve().parents[1]/'docs/TRUE_PLUGIN_CONVERSION.json';p.write_text(json.dumps(report,indent=2))
