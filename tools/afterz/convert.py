"""Fresh donor-only GLSL to SkSL conversion; output stays staged until native validation."""
from pathlib import Path
from lxml import etree as E
import argparse, collections, hashlib, json, re, sys, zipfile
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--workdir', type=Path, required=True, help='Private decoded donor workspace')
parser.add_argument('--apk', type=Path, required=True, help='Pinned user-supplied donor APK')
parser.add_argument('--skia-path', type=Path, help='Optional directory containing skia-python')
parser.add_argument('--prepare', action='store_true', help='Write shader-inputs.json before preprocessing')
parser.add_argument('--previews', type=Path, help='Optional native render snapshots from AfterZProbe')
ARGS = parser.parse_args()
if ARGS.skia_path:
    sys.path.insert(0, str(ARGS.skia_path))
import skia
ROOT = ARGS.workdir.resolve()
DONOR = ARGS.apk.resolve()
DONOR_SHA='18e671ed09a15538fed7f2a8c958123a1c2e30b9ac2ebf32530d7751db42293d'
CATEGORIES={'color':('Color & Light','Цвет и свет','颜色与光线'),'blur':('Blur & Glow','Размытие и свечение','模糊与辉光'),
 'distort':('Distortion','Искажения','扭曲'),'procedural':('Procedural','Генераторы','程序生成'),
 'drawing':('Drawing & Edges','Контур и рисование','绘制与边缘'),'transform':('Transform','Преобразования','变换'),
 '3d':('3D','3D','3D'),'text':('Text','Текст','文本'),'opacity':('Opacity','Непрозрачность','不透明度'),
 'matte':('Matte & Key','Маски и кеинг','蒙版与抠像'),'repeat':('Repeat','Повтор','重复'),'other':('Other','Прочее','其他')}

def resources():
    result=collections.defaultdict(dict); key=None
    for line in (ROOT/'resources-dump.txt').read_text(encoding='utf-8').splitlines():
        m=re.match(r'\s+resource \S+ string/(\S+)',line)
        if m:key=m[1]
        elif 'resource ' in line:key=None
        elif key and (m:=re.match(r'\s+\(([^)]*)\) (".*")$',line)):
            try: value=json.loads(m[2])
            except ValueError: value=m[2][1:-1].replace('\\"','"').replace('\\n','\n')
            result[key][m[1]]=value
    return result
RES=resources()
PREPROCESSED=json.loads((ROOT/'preprocessed.json').read_text()) if (ROOT/'preprocessed.json').exists() else {}

def numeric(s, default=0.):
    if s is None:return default
    return float(s.strip().replace('f',''))

def numbers(s, n=2, fallback=0.):
    if s is None:return [fallback]*n
    vals=[numeric(v) for v in re.split(r'[,\s]+',s.strip()) if v]
    return (vals*n)[:n] if len(vals)==1 else (vals+[fallback]*n)[:n]

def label(root,text,lang='en'):
    text=text or ''
    if text.startswith('@am:string/'):
        values=RES.get(text.split('/',1)[1],{})
        code={'en':'','ru':'ru-rRU','zh-CN':'zh-rCN'}.get(lang,lang)
        return values.get(code,values.get(lang,values.get('',text.split('/',1)[1].removeprefix('effect_param_').replace('_',' ').title())))
    if text.startswith('@string/'):
        key=text.split('/',1)[1]
        nodes=root.findall('./strings/locale')
        loc=next((n for n in nodes if n.get('lang')==lang), next((n for n in nodes if n.get('lang')=='en'),None))
        if loc is not None:
            val=next((n.text for n in loc if n.get('name')==key),None)
            if val:return val
        return key.replace('_',' ').title()
    return text

def params(root):
    result=[];section='';source=[]
    for node in root.findall('./params/*'):
        if not isinstance(node.tag,str):continue
        a=node.attrib; tag=node.tag
        if tag=='section':section=label(root,a.get('text',''));continue
        if tag in ('tip','texture'):continue
        if tag in ('orient','hue-disc','float'):raise ValueError('parameter widget '+tag)
        name=a.get('id'); item={'id':name,'label':label(root,a.get('label',name)),'shaderId':'amzp_'+name}
        if section:item['section']=section
        if tag in ('spinner','slider'):
            item.update(type='angle' if a.get('type')=='angle' else 'float',default=numeric(a.get('default')),min=numeric(a.get('min'),-10000.),max=numeric(a.get('max'),10000.),step=numeric(a.get('step'),.01))
            if a.get('type')=='percent':item['percent']=True
        elif tag in ('point','xyz'):
            count=3 if tag=='xyz' else 2
            lo=numbers(a.get('min'),count,-10000.);hi=numbers(a.get('max'),count,10000.)
            item.update(type='vector3' if count==3 else 'point',default=numbers(a.get('default'),count),min=min(lo),max=max(hi),step=numeric(a.get('step'),.01),componentMin=lo,componentMax=hi)
        elif tag=='color':
            c=a.get('default','#FFFFFFFF')
            if len(c)==7:c='#FF'+c[1:]
            item.update(type='color',default=c)
        elif tag=='switch':item.update(type='switch',default=a.get('default','false')=='true')
        elif tag=='selector':
            choices=node.findall('choice'); values=[numeric(c.get('value'),float(i)) for i,c in enumerate(choices)]
            item.update(type='choice',default=numeric(a.get('default')),options=[label(root,c.get('label',str(i))) for i,c in enumerate(choices)],optionValues=values)
        else:raise ValueError('parameter widget '+tag)
        if 'min' in item:
            vals=item['default'] if isinstance(item['default'],list) else [item['default']]
            # Keep source defaults even when the donor's declared slider range is narrower.
            item['min']=min(item['min'],*vals);item['max']=max(item['max'],*vals)
        if 'step' in item and item['step']<=0:item['step']=.01
        result.append(item);source.append(node)
    return result,source

HEADER='''#version 300
uniform shader image;
uniform float2 size;
uniform float2 layer;
uniform float3x3 fromLayer;
uniform float3x3 toLayer;
uniform float4 screen;
uniform float time;
float2 amzCoord;
float4 amzOutput;
float2 amzScreenNorm() { float2 p=amzCoord-screen.xy; return float2(p.x/screen.z,1.0-p.y/screen.w); }
float2 amzLayerNorm() { float3 p=toLayer*float3(amzCoord,1.0); float2 n=p.xy/p.z/layer; return float2(n.x,1.0-n.y); }
float2 amzLayerCenter() { float3 p=fromLayer*float3(layer*.5,1.0); return p.xy/p.z-screen.xy; }
float4x4 amzRowMatrix(float3x3 m) { return float4x4(m[0].x,m[1].x,0.0,m[2].x, m[0].y,m[1].y,0.0,m[2].y, 0.0,0.0,1.0,0.0, m[0].z,m[1].z,0.0,m[2].z); }
float4x4 amzLayerToScreen() {
    float3x3 toNorm=float3x3(1.0/screen.z,0.0,0.0, 0.0,-1.0/screen.w,0.0,-screen.x/screen.z,1.0+screen.y/screen.w,1.0);
    float3x3 toPixels=float3x3(layer.x,0.0,0.0, 0.0,-layer.y,0.0, 0.0,layer.y,1.0);
    return amzRowMatrix(toNorm*fromLayer*toPixels);
}
float4x4 amzScreenToLayer() {
    float3x3 toPixels=float3x3(screen.z,0.0,0.0, 0.0,-screen.w,0.0, screen.x,screen.y+screen.w,1.0);
    float3x3 toNorm=float3x3(1.0/layer.x,0.0,0.0, 0.0,-1.0/layer.y,0.0,0.0,1.0,1.0);
    return amzRowMatrix(toNorm*toLayer*toPixels);
}
float2 getTexSize(float2 v) { return v; }
int amzIntMax(int a,int b) { return int(max(float(a),float(b))); }
int amzIntMin(int a,int b) { return int(min(float(a),float(b))); }
int amzIntAbs(int a) { return int(abs(float(a))); }
int amzIntClamp(int a,int b,int c) { return int(clamp(float(a),float(b),float(c))); }
'''

AUTOMATIC={'acScreenNorm':'amzScreenNorm()','acLayerNorm':'amzLayerNorm()','acScreenSize':'screen.zw','acProjectSize':'screen.zw',
 'acPreviewSize':'screen.zw','acLayerSize':'layer','acLayerCenter':'amzLayerCenter()','acLayerCenterNorm':'(amzLayerCenter()/screen.zw)',
 'acLayerSizeNorm':'(layer/screen.zw)','acTime':'time','acLayerToScreen':'amzLayerToScreen()','acScreenToLayer':'amzScreenToLayer()',
 'acShowGuides':'false','acPass':'amzPass','acIter':'amzIter'}

def integer_helpers(body):
    names=set(re.findall(r'\bint\s+(\w+)',body))
    def integer(expr):
        s=expr.strip()
        return bool(re.fullmatch(r'[+-]?\d+[uU]?',s)) or s in names or s.startswith(('int(', 'amzInt'))
    def rewrite(text):
        found=list(re.finditer(r'\b(max|min|clamp|abs)\s*\(',text))
        for m in reversed(found):
            start=m.end(); depth=1; i=start; parts=[]; last=start
            while i<len(text) and depth:
                ch=text[i]
                if ch=='(':depth+=1
                elif ch==')':depth-=1
                elif ch==',' and depth==1:parts.append(text[last:i]);last=i+1
                i+=1
            if depth:continue
            parts.append(text[last:i-1]); parts=[rewrite(p) for p in parts]
            op=('amzInt'+m[1].title()) if all(integer(p) for p in parts) else m[1]
            text=text[:m.start()]+op+'('+','.join(parts)+')'+text[i:]
        return text
    return rewrite(body)

def shader(root, ps, body, pass_index, target_ds, texs):
    body=re.sub(r'/\*.*?\*/|//[^\n]*','',body,flags=re.S)
    body=re.sub(r'^\s*#(?:version|extension)[^\n]*','',body,flags=re.M)
    body=re.sub(r'\b(?:highp|mediump|lowp)\b','',body)
    body=re.sub(r'precision\s+\w+\s+\w+\s*;','',body)
    # Graphite appends a stage suffix: a source helper named gl becomes reserved gl_1.
    body=re.sub(r'\bgl\b', 'amzSource_gl', body)
    declared=set(re.findall(r'\b(?:mat[234]|vec[234]|float|bool|int)\s+(ac\w+)\b',body))
    unknown=set(re.findall(r'\bac[A-Z]\w*',body))-AUTOMATIC.keys()-declared
    if unknown:raise ValueError('runtime inputs '+','.join(sorted(unknown)))
    # Rename only source locals, before inserting any references to native uniforms.
    for name in ('pos','size','layer','time','screen','image','toLayer','fromLayer'):
        if name not in [p['id'] for p in ps]:body=re.sub(r'(?<!\.)\b'+name+r'\b','amzLocal_'+name,body)
    extra='const int amzPass='+str(pass_index)+';\nuniform float amzIteration;\n'
    # Iteration is supplied separately by the native graph, outside saved user parameters.
    AUTOMATIC['acIter']='int(amzIteration)'
    samples=[]
    for index,tex in enumerate(texs):
        name=tex['id'];kind=tex.get('srcType');ds=float(tex.get('downsample',1))
        child='image' if kind=='content' else 'backdrop' if kind=='comp' else 'amzt_'+name
        if child!='image':extra+='uniform shader '+child+';\n'
        if kind not in ('content','comp','buffer'):raise ValueError('texture source '+str(kind))
        extra+=f'float4 amzSample_{name}(float2 uv) {{ return float4({child}.eval((float2(uv.x,1.0-uv.y)*screen.zw+screen.xy)/{ds:.1f})); }}\n'
        samples.append(f'if(tex=={index}) return amzSample_{name}(uv);')
        body=re.sub(r'\b'+re.escape(name)+r'\.texture\b',str(index),body)
        body=re.sub(r'\b'+re.escape(name)+r'\.size\b',f'(screen.zw/{ds:.1f})',body)
    extra+='float4 amzTexture(int tex,float2 uv) { '+''.join(samples)+'return float4(0.0); }\n'
    body=re.sub(r'\bsampler2D\b','int',body)
    body=re.sub(r'\btexture2DCv\b','amzTexture',body)
    if re.search(r'\btexture(?:2D|3D)?\s*\(',body):raise ValueError('raw texture sampling')
    for p in ps:
        typ={'color':'half4','point':'float2','vector3':'float3'}.get(p['type'],'float')
        extra+=('layout(color) ' if p['type']=='color' else '')+'uniform '+typ+' '+p['shaderId']+';\n'
        value=p['shaderId']
        if p['type']=='switch':value='('+value+'>0.5)'
        if p['type']=='choice':value='int('+value+')'
        body=re.sub(r'\b'+re.escape(p['id'])+r'\b',value,body)
    for name,expr in AUTOMATIC.items():body=re.sub(r'\b'+name+r'\b',expr,body)
    body=re.sub(r'\bvec([234])\b',r'float\1',body);body=re.sub(r'\bbvec([234])\b',r'bool\1',body)
    body=re.sub(r'\bivec([234])\b',r'int\1',body);body=re.sub(r'\bmat([234])\b',r'float\1x\1',body)
    body=re.sub(r'void\s+main\s*\(\s*(?:void)?\s*\)', 'void amzRender()',body)
    body=re.sub(r'\bgl_FragColor\b','amzOutput',body)
    body=re.sub(r'\bgl_FragCoord\b',f'float4(amzCoord.x/{target_ds:.1f},(screen.w-amzCoord.y)/{target_ds:.1f},0.0,1.0)',body)
    if re.search(r'\b(?:mat[234]|float[234]x[234])\s+inverse\s*\(',body):
        body=re.sub(r'\binverse\b','amzCustomInverse',body)
    body=re.sub(r'\bdiscard\s*;',r'{ amzOutput=float4(0.0); return; }',body)
    body=integer_helpers(body)
    src=HEADER+extra+body+f'\nhalf4 main(float2 coord) {{ amzCoord=coord*{target_ds:.1f}; amzOutput=float4(0.0); amzRender(); return half4(amzOutput); }}\n'
    skia.RuntimeEffect.MakeForShader(src)
    return src

def main():
    assert hashlib.sha256(DONOR.read_bytes()).hexdigest()==DONOR_SHA
    if '--prepare' in sys.argv:
        sources={}
        for p in (ROOT/'effects-source').glob('*.xml'):
            r=E.fromstring(p.read_bytes(),E.XMLParser(recover=True))
            fragments=[s for s in r.findall('shader') if s.get('type')=='fragment']
            if len(fragments)==1:sources[p.stem]=fragments[0].text or ''
        (ROOT/'shader-inputs.json').write_text(json.dumps(sources),encoding='utf-8')
        print('Prepared',len(sources),'shader sources')
        return
    stage=ROOT/'converted';stage.mkdir(exist_ok=True)
    report=[]
    for p in sorted((ROOT/'effects-source').glob('*.xml'),key=lambda p:int(p.stem) if p.stem.isdigit() else 100000):
        raw=p.read_bytes();root=E.fromstring(raw,E.XMLParser(recover=True));a=dict(root.attrib)
        entry={'file':p.name,'sourceId':a['id'],'name':label(root,a.get('name',a['id'])),'sourceSha256':hashlib.sha256(raw).hexdigest(),'category':a.get('category','other')}
        try:
            if root.find('script') is not None:raise ValueError('JavaScript/geometry script')
            fragments=[s for s in root.findall('shader') if s.get('type')=='fragment']
            if root.find("shader[@type='vertex']") is not None:raise ValueError('vertex shader/geometry')
            if len(fragments)!=1:raise ValueError('native operation' if not fragments else 'shader groups')
            ps,nodes=params(root)
            texs=[dict(n.attrib) for n in root.findall('./params/texture')]
            passes=root.findall('./passes/pass') or [E.Element('pass')]
            textures={t['id']:t for t in texs}
            result=[]
            source=(fragments[0].text or '')
            if p.stem in PREPROCESSED:
                source=PREPROCESSED[p.stem]['source']
                if source is None:raise ValueError('preprocessor '+PREPROCESSED[p.stem]['error'])
            for i,desc in enumerate(passes):
                if desc.get('effect'):raise ValueError('native pass '+desc.get('effect'))
                if desc.get('blend'):raise ValueError('pass blend '+desc.get('blend'))
                target=desc.get('target','');ds=float(textures.get(target,{}).get('downsample',1))
                result.append({'target':target,'downsample':ds,'sksl':shader(root,ps,source,i,ds,texs)})
            key='afterz-'+p.stem
            obj={'id':key,'runtimeId':'amz.'+a['id'],'sourceId':a['id'],'name':entry['name'],'category':'amz-'+(a.get('category') if a.get('category') in CATEGORIES else 'other'),
                 'description':label(root,a.get('desc','')),'params':ps,'dialect':'afterz','sksl':result[-1]['sksl'],
                 'stages':result if len(result)>1 else [],'iterations':root.find('iterations').get('param') if root.find('iterations') is not None else None,
                 'textures':texs,'sourceFile':p.name,'sourceSha256':entry['sourceSha256']}
            (stage/(key+'.json')).write_text(json.dumps(obj,ensure_ascii=False,indent=1),encoding='utf-8')
            entry.update(status='compiled',key=key,stages=len(result),parameters=len(ps))
        except Exception as e:
            entry.update(status='pending',reason=str(e))
        report.append(entry)
    summary=collections.Counter(e['status'] for e in report)
    errors=collections.Counter(e.get('reason','').splitlines()[0] for e in report if e['status']!='compiled')
    (ROOT/'conversion.json').write_text(json.dumps({'donorSha256':DONOR_SHA,'summary':dict(summary),'effects':report},ensure_ascii=False,indent=2),encoding='utf-8')
    print(summary);print(errors.most_common(22))

if __name__=='__main__':main()
