"""Build a self-contained, offline design preview using only the generic car crop."""
from pathlib import Path
from html.parser import HTMLParser
from PIL import Image
import argparse, base64, io
ROOT=Path(__file__).resolve().parent
class Capture(HTMLParser):
    def __init__(self): super().__init__(); self.image=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=='img' and a.get('alt')=='SCR-01': self.image=a.get('src')
parser=argparse.ArgumentParser()
parser.add_argument('--evidence',type=Path,default=ROOT.parent/'ui-evidence/Tesla_MateLink_UI_Full_Handoff.html')
args=parser.parse_args()
p=Capture();p.feed(args.evidence.read_text())
assert p.image and p.image.startswith('data:image/png;base64,')
im=Image.open(io.BytesIO(base64.b64decode(p.image.split(',',1)[1])))
assert im.size==(1440,3120), 'Unexpected evidence dimensions: inspect before cropping'
# This bounded rectangle contains the generic existing vehicle render, not identity/map data.
car=im.crop((390,690,1110,1090)).convert('RGB').resize((240,134),Image.Resampling.BICUBIC)
b=io.BytesIO();car.save(b,format='JPEG',quality=50,optimize=True)
(ROOT/'vehicle-reference.jpg').write_bytes(b.getvalue())
s=(ROOT/'preview.template.html').read_text();assert s.count('__CAR__')==1
s=s.replace('__CAR__','data:image/jpeg;base64,'+base64.b64encode(b.getvalue()).decode())
(ROOT/'preview.html').write_text(s)
