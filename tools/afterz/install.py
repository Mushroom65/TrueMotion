from pathlib import Path
import collections, hashlib, io, json, shutil, sys, zipfile
from PIL import Image, ImageOps, ImageDraw
from lxml import etree as E
import convert as converter

repo=Path(__file__).resolve().parents[2]
pack=repo/'app/src/main/assets/effects'
source=converter.ROOT
assert hashlib.sha256(converter.DONOR.read_bytes()).hexdigest()==converter.DONOR_SHA, 'Donor APK fingerprint differs'
report=json.loads((source/'conversion.json').read_text(encoding='utf-8'))
assert report['donorSha256']==converter.DONOR_SHA
compiled=[e for e in report['effects'] if e['status']=='compiled']
index=json.loads((pack/'index.json').read_text())
original=[k for k in index if not k.startswith('afterz-')]
foreign=json.loads((pack/'foreign.json').read_text(encoding='utf-8'))
existing_foreign={k:v for k,v in foreign.items() if not str(v.get('to','')).startswith('afterz-')}
foreign=dict(existing_foreign)
baseline={k:hashlib.sha256((pack/(k+'.json')).read_bytes()).hexdigest() for k in original}
locales={p.stem:(p,json.loads(p.read_text(encoding='utf-8'))) for p in (pack/'i18n').glob('*.json')}
ui_text=json.loads((Path(__file__).parent/'ui_text.json').read_text(encoding='utf-8'))
common={lang:{} for lang in ('ru','zh-CN')}
for key in original:
    for p in json.loads((pack/(key+'.json')).read_text(encoding='utf-8')).get('params',[]):
        for lang in common:
            t=locales[lang][1].get('effects',{}).get(key,{}).get('params',{}).get(p['id'],{}).get('label')
            if t:common[lang].setdefault(p.get('label',p['id']),t)
def translated(root,text,lang):
    value=converter.label(root,text,lang)
    if value!=converter.label(root,text,'en'):return value
    return ui_text.get(value,{}).get(lang,common.get(lang,{}).get(value,value))
preview_sources={}
with zipfile.ZipFile(converter.DONOR) as donor:
    donor_names=donor.namelist()
    donor_name_set=set(donor_names)
    by_stem={n.rsplit('.',1)[0]:n for n in donor_names}
    resources={}
    for n in donor_names:
        if n.startswith('res/drawable'):
            stem=Path(n).stem
            resources[stem]=n
            if 'thumb_' in stem:resources[stem.split('thumb_',1)[1]]=n
    for entry in compiled:
        key=entry['key'];obj=json.loads((source/'converted'/(key+'.json')).read_text(encoding='utf-8'))
        root=E.fromstring((source/'effects-source'/entry['file']).read_bytes(),E.XMLParser(recover=True))
        for p in obj['params']:
            if p.get('section'):p['section']=converter.label(root,p['section'])
        (pack/(key+'.json')).write_text(json.dumps(obj,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
        if obj['sourceId'] not in foreign:
            foreign[obj['sourceId']]={'to':key,'params':{p['id']:{'to':p['id']} for p in obj['params']}}
        thumb=root.get('thumb','');name='assets/effects/'+thumb
        if name not in donor_name_set:
            name=by_stem.get(name.rsplit('.',1)[0],resources.get(Path(thumb).stem))
        if name:
            image=Image.open(io.BytesIO(donor.read(name))).convert('RGBA')
            image=ImageOps.contain(image,(128,128),Image.Resampling.LANCZOS)
            preview_sources[key]={'type':'donor','path':name}
        else:
            frame=converter.ARGS.previews/('render-'+key+'.png') if converter.ARGS.previews else None
            image=Image.new('RGBA',(128,128),(45,49,65,255))
            draw=ImageDraw.Draw(image)
            for y in range(0,128,16):
                for x in range(0,128,16):
                    if (x//16+y//16)%2:draw.rectangle((x,y,x+15,y+15),fill=(55,60,76,255))
            if frame and frame.is_file():
                image.alpha_composite(Image.open(frame).convert('RGBA'))
                preview_sources[key]={'type':'native-snapshot','sha256':hashlib.sha256(frame.read_bytes()).hexdigest()}
            else:preview_sources[key]={'type':'transparency-placeholder'}
        image.save(pack/'thumb'/(key+'.webp'),format='WEBP',quality=84,method=6)
        nodes={n.get('id'):n for n in root.findall('./params/*') if isinstance(n.tag,str) and n.get('id')}
        for lang,(path,local) in locales.items():
            strings={}
            for p in obj['params']:
                node=nodes[p['id']]
                strings[p['id']]={'label':translated(root,node.get('label',p['id']),lang),'section':translated(root,p.get('section',''),lang)}
                if p['type']=='choice':strings[p['id']]['options']=[converter.label(root,c.get('label',str(i)),lang) for i,c in enumerate(node.findall('choice'))]
            local.setdefault('effects',{})[key]={'name':converter.label(root,root.get('name',entry['name']),lang),
                'description':converter.label(root,root.get('desc',''),lang),'params':strings}
for lang,(path,local) in locales.items():
    language=1 if lang=='ru' else 2 if lang=='zh-CN' else 0
    for cat,names in converter.CATEGORIES.items():local.setdefault('categories',{})['amz-'+cat]='AfterMotionZ+ · '+names[language]
    path.write_text(json.dumps(local,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
(pack/'index.json').write_text(json.dumps(original+[e['key'] for e in compiled],indent=1)+'\n',encoding='utf-8')
(pack/'foreign.json').write_text(json.dumps(foreign,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert all(hashlib.sha256((pack/(k+'.json')).read_bytes()).hexdigest()==v for k,v in baseline.items())
manifest={'sourceApk':'AfterMotionZ+ Clone.apk','sourceApkSha256':converter.DONOR_SHA,'sourceDefinitions':834,
    'converted':len(compiled),'pending':len(report['effects'])-len(compiled),'originalDefinitionsUnchanged':baseline,
    'oldTrueMotionCatalogUsedAsSource':False,'thumbnailSources':preview_sources,
    'originalImportRulesSha256':hashlib.sha256(json.dumps(existing_foreign,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
    'newImportRules':len(foreign)-len(existing_foreign),
    'categories':dict(collections.Counter(json.loads((pack/(e['key']+'.json')).read_text(encoding='utf-8'))['category'] for e in compiled)),
    'effects':[{k:v for k,v in e.items() if k!='reason'}|({'reason':e['reason'].splitlines()[0]} if 'reason' in e else {}) for e in report['effects']]}
(repo/'docs/AFTERZ_EFFECT_PORT.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Installed',len(compiled),'fresh donor effects;',len(original),'existing definitions unchanged')
