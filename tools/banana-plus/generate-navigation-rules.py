"""Derive document destination hosts from the browser's existing filter snapshots."""
import argparse, hashlib, json, re
from pathlib import Path
BASE = Path(__file__).parent
DOMAIN = r'(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]+'
BLOCK = re.compile(r'^\|\|(' + DOMAIN + r')\^(?:\$(all|document))?$')
EXCEPTION = re.compile(r'^@@\|\|(' + DOMAIN + r')(?=[\^/$])')
RESOURCE_TYPES = {'image','script','xhr','xmlhttprequest','frame','subdocument','stylesheet','font','media','object','ping','websocket','other','popup'}
FILES = ['base.txt','easylist.txt','badware.txt','filters.txt','serverlist.txt','unbreak.txt']
def collect(lines):
 blocks, allows = set(), set()
 for line in lines:
  line = line.strip()
  m = BLOCK.fullmatch(line)
  if m: blocks.add(m[1])
  e = EXCEPTION.match(line)
  if e:
   options = line.split('$', 1)[1].split(',') if '$' in line else []
   if not RESOURCE_TYPES.intersection(options) or {'document','all'}.intersection(options): allows.add(e[1])
 return blocks, allows

def generate(source):
 provenance = {r['file']:r for r in json.loads((source/'download-provenance.json').read_text('utf8'))}
 blocks, allows, sources = set(), set(), []
 for name in FILES:
  data = (source/name).read_bytes(); sha = hashlib.sha256(data).hexdigest()
  assert sha == provenance[name]['sha256']
  b, a = collect(data.decode('utf8').splitlines()); blocks.update(b); allows.update(a)
  sources.append({'file':name,'url':provenance[name]['url'],'sha256':sha})
 def excepted(h): return any('.'.join(h.split('.')[i:]) in allows for i in range(len(h.split('.'))-1))
 blocks = {h for h in blocks if not excepted(h)}
 lines = ['# Banana Plus native destination policy v1; +block, -exception'] + ['+'+h for h in sorted(blocks)] + ['-'+h for h in sorted(allows)]
 data = ('\n'.join(lines)+'\n').encode()
 (BASE/'chromext/app/src/main/assets/banana-navigation-hosts.txt').write_bytes(data)
 report = {'schema':1,'generated':'2026-10-16','block_hosts':len(blocks),'exception_hosts':len(allows),'asset_sha256':hashlib.sha256(data).hexdigest(),'selection':'Only global whole-host rules without options or with all/document alone. Domain-scoped, third-party, resource-specific, regex and path block rules are not broadened. Applicable document exceptions win; resource-only exceptions do not whitelist main documents.','sources':sources}
 (BASE/'navigation-rules-provenance.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({k:v for k,v in report.items() if k!='sources'}))
if __name__ == '__main__':
 parser = argparse.ArgumentParser(); parser.add_argument('--source-dir', type=Path, required=True)
 generate(parser.parse_args().source_dir)
