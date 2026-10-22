#!/usr/bin/env python3
"""Prepare lossless launcher/in-app sizes from the supplied artwork, without redrawing it."""
from pathlib import Path
from PIL import Image, ImageChops
import io
root = Path(__file__).resolve().parents[1]
source = Image.open(root / 'artwork/insfav-logo.png').convert('RGB')

def save(image, path):
    data = io.BytesIO()
    image.save(data, format='WEBP', lossless=True, method=6)
    decoded = Image.open(io.BytesIO(data.getvalue())).convert('RGB')
    if ImageChops.difference(image, decoded).getbbox() is not None:
        raise ValueError('Lossless pixel validation failed')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data.getvalue())

for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    folder = root / ('app/src/main/res/mipmap-' + density)
    save(source.resize((size,size), Image.Resampling.LANCZOS), folder/'ic_launcher.webp')
    (folder/'ic_launcher.png').unlink(missing_ok=True)
canvas = Image.new('RGB',(432,432),'black')
canvas.paste(source.resize((264,264),Image.Resampling.LANCZOS),(84,84))
save(canvas,root/'app/src/main/res/drawable-nodpi/ic_launcher_foreground.webp')
(root/'app/src/main/res/drawable-nodpi/ic_launcher_foreground.png').unlink(missing_ok=True)
save(source.resize((256,256),Image.Resampling.LANCZOS),root/'app/src/main/assets/logo.webp')
print('Launcher, adaptive foreground and in-app images prepared with lossless pixel checks.')
