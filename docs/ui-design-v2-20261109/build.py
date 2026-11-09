"""Build the offline V2 review; do not bundle private evidence or fonts."""
from pathlib import Path
from html.parser import HTMLParser
import base64, io, json, hashlib
from PIL import Image
ROOT=Path(__file__).resolve().parent
class Capture(HTMLParser):
    def __init__(self): super().__init__(); self.image=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=='img' and a.get('alt')=='SCR-01': self.image=a.get('src')
src=ROOT.parent/'ui-evidence/Tesla_MateLink_UI_Full_Handoff.html'
p=Capture();p.feed(src.read_text(encoding='utf-8'))
assert p.image and p.image.startswith('data:image/png;base64,')
im=Image.open(io.BytesIO(base64.b64decode(p.image.split(',',1)[1])))
assert im.size==(1440,3120)
# Generic car image only. This region has no account, maps, coordinates or status UI.
car=im.crop((390,690,1110,1090)).convert('RGB').resize((480,267),Image.Resampling.LANCZOS)
buf=io.BytesIO();car.save(buf,format='JPEG',quality=82,optimize=True)
s=(ROOT/'preview.template.html').read_text(encoding='utf-8');assert s.count('__CAR__')==1
s=s.replace('__CAR__','data:image/jpeg;base64,'+base64.b64encode(buf.getvalue()).decode())
(ROOT/'preview.html').write_text(s,encoding='utf-8')
receipt={'app_evidence_sha':'929a63eeb46f36feb9295400da96d5382127068d','mini_evidence_sha':'0913bf3795069e02a03dcae2c02dc8b05c09651e','supersedes_design_source':'1e581b3b3274062ab9d3f2264a4a911c8928ac16','scope':'offline design refinement, not native app implementation','figma_updated':False,'figma_failure':'Starter tool-call limit; read rejected on current attempt','contains_synthetic_data_only':True,'production_requests':0,'fonts_bundled':False,'car_crop':[390,690,1110,1090],'preview_sha256':hashlib.sha256(s.encode()).hexdigest()}
(ROOT/'receipt.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(receipt,ensure_ascii=False))
