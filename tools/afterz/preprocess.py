from pathlib import Path
import argparse, concurrent.futures, json, re, subprocess, shutil

parser=argparse.ArgumentParser(description='Expand donor GLSL macros without file includes')
parser.add_argument('--workdir',type=Path,required=True)
root=parser.parse_args().workdir.resolve()
inputs=json.loads((root/'shader-inputs.json').read_text())
def convert(item):
    name,src=item
    directives=set(re.findall(r'^\s*#\s*(\w+)',src,re.M))
    assert not directives-{'define','undef','if','ifdef','ifndef','elif','else','endif'},(name,directives)
    compiler=shutil.which('clang') or shutil.which('cpp') or shutil.which('gcc')
    p=subprocess.run([compiler,'-E','-P','-x','c','-undef','-nostdinc','-DGL_ES=1','-DGL_FRAGMENT_PRECISION_HIGH=1','-'],input=src.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    return name, {'source':p.stdout.decode() if p.returncode==0 else None,'error':p.stderr.decode()}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    result=dict(pool.map(convert,inputs.items()))
(root/'preprocessed.json').write_text(json.dumps(result),encoding='utf-8')
print('Preprocessed',len(result),'shader sources; errors',sum(v['source'] is None for v in result.values()))
