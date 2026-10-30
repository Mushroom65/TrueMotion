"""Generate the owned H.264 fixture used to verify hidden video textures."""
from pathlib import Path
import argparse, subprocess, tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--ffmpeg',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
out=root/'app/src/androidTest/assets/particle-reference.mp4'
out.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as folder:
    ppm=Path(folder)/'reference.ppm'
    rgb=bytearray(320*320*3)
    for y in range(90,230):
        for x in range(90,230): rgb[(y*320+x)*3:(y*320+x)*3+3]=bytes((0,217,165))
    ppm.write_bytes(b'P6\n320 320\n255\n'+rgb)
    subprocess.run([args.ffmpeg,'-v','error','-y','-loop','1','-i',str(ppm),'-t','1','-r','30','-c:v','libx264','-pix_fmt','yuv420p',str(out)],check=True)
print(out)
