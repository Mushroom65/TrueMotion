"""Port the owned legacy particle schema; preserve names and explicit defaults.

Run with --legacy path/to/lab_particles3d.xml. No shader code is copied: the
generator is implemented independently in particles_math.h / particles.cpp.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--legacy', type=Path, required=True)
args = parser.parse_args()
legacy = ET.parse(args.legacy).getroot()
assets = root / 'app/src/main/assets/effects'

# English and Chinese parameter labels, in legacy schema order.
labels = {
 'amount': ('Particle count', '粒子数量'), 'life': ('Lifetime (s)', '生命周期（秒）'),
 'lifeRandom': ('Lifetime randomness', '寿命随机性'), 'timeScale': ('Time speed', '时间速度'),
 'timeOffset': ('Time offset (s)', '时间偏移（秒）'), 'seed': ('Seed', '随机种子'),
 'emitter': ('Emitter shape', '发射器形状'), 'origin': ('Position', '位置'),
 'spawnX': ('Spawn radius X', '生成半径 X'), 'spawnY': ('Spawn radius Y', '生成半径 Y'), 'spawnZ': ('Spawn radius Z', '生成半径 Z'),
 'velocity': ('Velocity', '速度'), 'spread': ('Velocity spread', '速度散布'),
 'directionSpread': ('Direction cone (°)', '方向锥角（度）'), 'velocityRandom': ('Velocity randomness', '速度随机性'),
 'speedStart': ('Speed at birth ×', '出生速度倍数'), 'speedEnd': ('Speed at death ×', '结束速度倍数'),
 'gravityX': ('Gravity X', '重力 X'), 'gravity': ('Gravity Y', '重力 Y'), 'gravityZ': ('Gravity Z', '重力 Z'),
 'wind': ('Wind acceleration', '风加速度'), 'forceRandom': ('Force randomness', '力随机性'),
 'drag': ('Drag', '阻力'), 'turbulence': ('Turbulence', '湍流'), 'noiseFrequency': ('Noise frequency', '噪声频率'),
 'attractor': ('Attractor position', '吸引器位置'), 'attractStrength': ('Attract (+) / repel (−)', '吸引（+）/ 排斥（−）'),
 'attractRadius': ('Attractor soft radius', '吸引器软化半径'), 'radius': ('Particle radius', '粒子半径'),
 'sizeStart': ('Size at birth ×', '出生大小倍数'), 'sizeEnd': ('Size at death ×', '结束大小倍数'),
 'lifeCurve': ('Lifetime curve', '生命周期曲线'), 'variation': ('Size randomness', '大小随机性'),
 'tint': ('Tint', '整体颜色'), 'colorStart': ('Color at birth ×', '出生颜色倍数'), 'colorEnd': ('Color at death ×', '结束颜色倍数'),
 'colorRandom': ('Color randomness', '颜色随机性'), 'opacityStart': ('Opacity at birth', '出生不透明度'),
 'opacityEnd': ('Opacity at death', '结束不透明度'), 'opacityRandom': ('Opacity randomness', '不透明度随机性'),
 'softFade': ('Fade in / out', '淡入淡出'), 'rotation': ('Rotation (°)', '旋转（度）'),
 'rotationRandom': ('Rotation randomness ±', '旋转随机范围'), 'rotationSpeed': ('Rotation speed (°/s)', '旋转速度（度/秒）'),
 'spinRandom': ('Spin randomness', '旋转速度随机性'), 'alignVelocity': ('Align to velocity', '沿速度方向对齐'),
 'useTexture': ('Use texture', '使用纹理'), 'additive': ('Additive blending', '叠加混合'),
 'camera': ('Emitter camera position', '发射器相机位置'), 'camRotation': ('Emitter camera rotation', '发射器相机旋转'),
 'fov': ('Field of view (°)', '视野角（度）'),
}
params, sections, conversions, legacy_defaults = [], [], {}, {}
translations = {tag: {} for tag in ('ru', 'zh-CN')}
for node in legacy.find('params'):
    if node.tag == 'section':
        sections.append([])
        continue
    if node.tag in ('tip', 'texture'):
        continue
    name = node.get('id')
    en, zh = labels[name]
    ru = node.get('label')
    values = node.get('default').split(',')
    axes = 'XYZ' if node.tag == 'xyz' else ['']
    mapped = []
    for i, axis in enumerate(axes):
        key = name + axis
        p = {'id': key, 'label': en + (' '+axis if axis else ''), 'type': 'float', 'default': float(values[i]) if node.tag not in ('switch','color','selector') and name != 'seed' else 0}
        if node.tag == 'xyz':
            p.update(min=-10000, max=10000, step=.01)
        elif name == 'seed':
            p.update(type='text', default=node.get('default'))
        elif node.tag == 'color':
            p.update(type='color', default=node.get('default'))
        elif node.tag == 'switch':
            p.update(type='switch', default=node.get('default') == 'true')
        elif node.tag == 'selector':
            p.update(type='choice', default=int(node.get('default')), options=['Point', 'Sphere', 'Box', 'Circle'])
        else:
            p.update(min=float(node.get('min')), max=float(node.get('max')), step=float(node.get('step', '.01')))
        legacy_defaults[key] = p['default']
        params.append(p); sections[-1].append(key)
        suffix = ' '+axis if axis else ''
        translations['ru'][key] = {'label': ru + suffix}
        translations['zh-CN'][key] = {'label': zh + suffix}
        mapped.append({'to': key, **({'component': i} if axis else {})})
    conversions[name] = mapped if len(mapped) > 1 else mapped[0]

for key, default, low, high in [('worldScale',300,1,100000),('spriteAspect',1,.01,100),('textureResolution',512,64,1024)]:
    title = {'worldScale': ('World unit (px)', 'Размер единицы (px)', '世界单位（像素）'),
             'spriteAspect': ('Texture aspect X/Y', 'Пропорции текстуры X/Y', '纹理宽高比 X/Y'),
             'textureResolution': ('Texture detail (px)', 'Детализация текстуры (px)', '纹理精度（像素）')}[key]
    params.append({'id':key,'label':title[0],'type':'float','default':default,'min':low,'max':high,'step':1 if key != 'spriteAspect' else .01})
    translations['ru'][key]={'label':title[1]}; translations['zh-CN'][key]={'label':title[2]}
    sections[4 if key == 'worldScale' else 3].append(key)
params.append({'id':'sourceLayer','label':'Texture source layer','type':'text','default':'0'})
translations['ru']['sourceLayer']={'label':'Слой-источник текстуры'}
translations['zh-CN']['sourceLayer']={'label':'纹理源图层'}
translations['ru']['emitter']['options']=['Точка','Сфера','Объём','Круг']
translations['zh-CN']['emitter']['options']=['点','球体','盒体','圆形']

overrides={'amount':128,'timeOffset':1,'spawnX':.6,'spawnY':.15,'sizeEnd':0,'opacityEnd':0}
for p in params:
    if p['id'] in overrides: p['default']=overrides[p['id']]
    if p['id']=='amount': p['max']=4096
    if p['id']=='life': p.update(min=.05,max=60)

def save(path, obj):
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
key='true-particles'
save(assets/(key+'.json'),dict(id=key,runtimeId='lab.true.particles',name='Particles',category='true',native=True,
    description='Native deterministic particle physics. Four emitters, lifetime controls, XYZ forces, drag, turbulence, attraction and a layer texture. Opens a dedicated panel.',params=params))
index=json.loads((assets/'index.json').read_text(encoding='utf-8'))
if key not in index: index.append(key)
save(assets/'index.json',index)
for file in (assets/'i18n').glob('*.json'):
    data=json.loads(file.read_text(encoding='utf-8'))
    tag=file.stem
    entry={'name':'Частицы' if tag=='ru' else '粒子' if tag=='zh-CN' else 'Particles',
           'description':'Нативная физика частиц, параметры по жизни и текстура другого слоя.' if tag=='ru' else '原生粒子物理、生命周期参数和其他图层纹理。' if tag=='zh-CN' else 'Native particle physics, lifetime controls and a layer texture.',
           'params':translations.get(tag,{p['id']:{'label':p['label'],**({'options':p['options']} if 'options' in p else {})} for p in params})}
    data.setdefault('effects',{})[key]=entry
    save(file,data)
foreign=json.loads((assets/'foreign.json').read_text(encoding='utf-8'))
# Explicit old defaults keep omitted fields from adopting new generator defaults.
foreign['lab.toji.particles3d']={'to':key,'params':conversions,'set':legacy_defaults}
save(assets/'foreign.json',foreign)
save(root/'docs/TRUE_PARTICLES_SCHEMA.json',{'legacySHA256':hashlib.sha256(args.legacy.read_bytes()).hexdigest(),
    'legacyId':legacy.get('id'),'runtimeId':'lab.true.particles','sections':sections,'params':len(params),
    'implementation':'C++ fixed-step physics and Skia geometry; no legacy fragment shader'})
print('Ported',len(params),'parameters')
