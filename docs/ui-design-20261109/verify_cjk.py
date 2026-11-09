"""Read-only font coverage gate. Never installs or distributes a font."""
from pathlib import Path
import subprocess, json
R=Path(__file__).resolve().parent
font=subprocess.check_output(['fc-match','sans-serif:lang=zh-cn','-f','%{file}\n'],text=True).splitlines()[0]
charset=subprocess.check_output(['fc-query','-f','%{charset}\n',font],text=True)
ranges=[]
for token in charset.split():
    parts=token.split('-')
    ranges.append((int(parts[0],16),int(parts[-1],16)))
chars=sorted({c for c in (R/'preview.template.html').read_text() if '\u4e00'<=c<='\u9fff'})
missing=[c for c in chars if not any(a<=ord(c)<=b for a,b in ranges)]
assert chars and not missing, 'Chinese coverage missing: '+''.join(missing[:30])
receipt={'font_file_name':Path(font).name,'chinese_codepoints_checked':len(chars),'missing':[], 'scope':'isolated runner font coverage; font files are NOT artifacts'}
(R/'font-coverage.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2))
print(json.dumps(receipt,ensure_ascii=False))
