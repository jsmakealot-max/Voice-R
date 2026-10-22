"""런처 아이콘(초록 원 + 흰 마이크)을 밀도별 PNG로 만든다. 사용법: python3 make_icons.py <res 폴더>"""
import os
import sys
from PIL import Image, ImageDraw

SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def icon(size):
    s = size * 4  # 크게 그린 뒤 줄여서 가장자리를 부드럽게
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((s * .04, s * .04, s * .96, s * .96), fill=(16, 185, 129, 255))
    w = (255, 255, 255, 255)
    d.rounded_rectangle((s * .40, s * .22, s * .60, s * .56), radius=s * .10, fill=w)
    lw = max(2, int(s * .045))
    d.arc((s * .31, s * .32, s * .69, s * .66), 0, 180, fill=w, width=lw)
    d.line((s * .5, s * .66, s * .5, s * .76), fill=w, width=lw)
    d.line((s * .40, s * .76, s * .60, s * .76), fill=w, width=lw)
    return im.resize((size, size), Image.LANCZOS)


for name, px in SIZES.items():
    folder = os.path.join(sys.argv[1], f'mipmap-{name}')
    os.makedirs(folder, exist_ok=True)
    icon(px).save(os.path.join(folder, 'ic_launcher.png'))
