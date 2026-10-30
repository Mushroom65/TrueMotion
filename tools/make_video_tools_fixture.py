"""Generate an owned red/green video with a 50 Hz audio tone for frame/speed regression."""
from pathlib import Path
import argparse, subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--ffmpeg',required=True)
args=parser.parse_args()
target=Path(__file__).resolve().parents[1]/'app/src/androidTest/assets/video-tools.mp4'
target.parent.mkdir(parents=True,exist_ok=True)
subprocess.run([args.ffmpeg,'-v','error','-y','-f','lavfi','-i','color=c=red:s=160x96:r=30:d=8',
    '-f','lavfi','-i','sine=frequency=50:sample_rate=48000:duration=8',
    '-vf',"drawbox=x=0:y=0:w=iw:h=ih:color=0x00d9a5:t=fill:enable='gte(t,4)'",
    '-c:v','libx264','-pix_fmt','yuv420p','-g','30','-c:a','aac','-b:a','128k','-shortest',str(target)],check=True)
print(target)
