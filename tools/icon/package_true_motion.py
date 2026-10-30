#!/usr/bin/env python3
"""Package the supplied True Motion artwork without redrawing it. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]

def main():
    source = Image.open(ROOT / 'branding/true-motion-source.png').convert('RGBA')
    # Adaptive icons use a 108 dp canvas. Keep all artwork within its safe circle.
    mark = ImageOps.contain(source, (264, 264), Image.Resampling.LANCZOS)
    foreground = Image.new('RGBA', (432, 432))
    foreground.alpha_composite(mark, ((432-mark.width)//2, (432-mark.height)//2))
    monochrome = Image.new('RGBA', foreground.size, 'white')
    monochrome.putalpha(ImageOps.grayscale(foreground))
    drawable = ROOT / 'app/src/main/res/drawable'
    foreground.save(drawable / 'true_motion_launcher_foreground.png', optimize=True)
    monochrome.save(drawable / 'true_motion_launcher_monochrome.png', optimize=True)
    logo = ImageOps.contain(source, (512, 512), Image.Resampling.LANCZOS)
    for path in (drawable / 'true_motion_logo.png', ROOT / 'branding/icon.png',
                 ROOT / 'fastlane/metadata/android/en-US/images/icon.png'):
        logo.save(path, optimize=True)
    print('Packaged app, adaptive and themed icons from the original PNG')

if __name__ == '__main__':
    main()
