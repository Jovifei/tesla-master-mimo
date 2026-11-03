from pathlib import Path
import base64, hashlib, subprocess, zlib
root=Path(__file__).parent
parts=[(root/f'app-patch.{i}.b64').read_text().strip() for i in (1,2,3)]
# Correct the one transport transcription; the decoded source digest is mandatory.
parts[2]=parts[2].replace('Db/3usxO74D98F','Db/3usx98F')
patch=zlib.decompress(base64.b64decode(''.join(parts), validate=True))
assert hashlib.sha256(patch).hexdigest()=='3014792799406a451576e4e870c080f85e3e6e5c2e81dbf07727f89e2b36ba07', 'source transport mismatch'
Path('/tmp/interaction-source.patch').write_bytes(patch)
subprocess.run(['git','apply','--check','-'],input=patch,check=True)
subprocess.run(['git','apply','--index','-'],input=patch,check=True)
