"""Convert the owned True Motion pointwise grade into a Skia runtime effect.

This is a deliberately restricted adapter, not a general Alight shader converter.
Input IDs, defaults, ranges, operation order and premultiplied alpha are retained.
"""
import argparse
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET


def convert(source):
    root = ET.fromstring(source)
    assert root.attrib['id'] == 'lab.true.grade'
    params = []
    for p in root.find('params'):
        if p.tag == 'texture':
            assert p.attrib['id'] == 'inputImg'
            continue
        assert p.tag == 'spinner', p.tag
        a = p.attrib
        params.append(dict(id=a['id'], label=a.get('label', a['id']), type='float',
                           default=float(a['default']), min=float(a['min']),
                           max=float(a['max']), step=float(a['step'])))
    body = root.find('shader').text
    assert body.count('texture2DCv(inputImg.texture,acScreenNorm)') == 1
    body = body.replace('texture2DCv(inputImg.texture,acScreenNorm)', 'float4(image.eval(coord))')
    body = body.replace('void main()', 'half4 main(float2 coord)')
    body = re.sub(r'\bvec([234])\b', r'float\1', body)
    body = body.replace('gl_FragColor=', 'return ')
    header = 'uniform shader image;\nuniform float2 size;\nuniform float time;\n' + ''.join('uniform float ' + p['id'] + ';\n' for p in params)
    return dict(id='true-grade', runtimeId=root.attrib['id'], name='True Color Grade',
                category='true', description='True Motion color grading: primary controls, Lift/Gamma/Gain, RGB curves and six HSL bands. One texture pass; animated parameters are supported.',
                params=params, sksl=header + body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('source', type=pathlib.Path)
    args = ap.parse_args()
    target = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/effects'
    raw = args.source.read_bytes()
    result = convert(raw)
    (target / 'true-grade.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    index = json.loads((target / 'index.json').read_text())
    if 'true-grade' not in index:
        index.append('true-grade')
    (target / 'index.json').write_text(json.dumps(index, indent=1) + '\n')
    provenance = dict(source='True Motion work/modassets/true_grade.xml', sha256=hashlib.sha256(raw).hexdigest(),
                      output='effects/true-grade.json', adapter='tools/port_true_grade.py', params=len(result['params']))
    (target.parent / 'true-grade-provenance.json').write_text(json.dumps(provenance, indent=2))
    print(json.dumps(provenance))


if __name__ == '__main__':
    main()
