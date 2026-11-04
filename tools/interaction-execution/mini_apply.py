from pathlib import Path
import base64, hashlib, lzma, subprocess
root=Path(__file__).parent
payload=''.join((root/f'mini-patch-{i}.b64').read_text().strip() for i in range(2))
patch=lzma.decompress(base64.b64decode(payload, validate=True))
assert hashlib.sha256(patch).hexdigest()=='d58b3b83ae996d56c3d4163a08c51f3b2d743d5dc87a1e2943cf3a8556696c59', 'source transport mismatch'
subprocess.run(['git','apply','--check','--index','-'], input=patch, check=True)
subprocess.run(['git','apply','--index','-'], input=patch, check=True)
# Button is already a native control; the locked Taro ButtonProps does not
# support ariaRole. Preserve its ariaLabel, focus and real click handler.
p=Path('miniprogram/src/components/interaction/InteractionVisuals.tsx')
s=p.read_text(); invalid=' ariaRole="button"'
assert s.count(invalid)==1
p.write_text(s.replace(invalid,''))
subprocess.run(['git','add',str(p)],check=True)
paths=subprocess.check_output(['git','diff','--cached','--name-only'],text=True).splitlines()
assert len(paths)==27, paths
assert all(p.startswith(('miniprogram/src/','docs/')) for p in paths), paths
assert not any(p.endswith(('package.json','package-lock.json')) for p in paths)
print('Applied reviewed consumer source; owner 0.6.5 tooling and package versions preserved.')
