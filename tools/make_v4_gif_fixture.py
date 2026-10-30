from pathlib import Path
from PIL import Image, ImageDraw
frames=[]
for index,color in enumerate([(255,0,0,255),(0,255,0,255),(0,0,255,255)]):
    image=Image.new('RGBA',(64,64),(0,0,0,0))
    ImageDraw.Draw(image).rectangle((8+index*4,12,40+index*4,50),fill=color)
    frames.append(image)
path=Path(__file__).resolve().parents[1]/'app/src/androidTest/assets/animated-v4.gif'
path.parent.mkdir(parents=True,exist_ok=True)
frames[0].save(path,save_all=True,append_images=frames[1:],duration=[100,100,100],loop=0,disposal=2)
print(path.name,path.stat().st_size)
