"""Filter selection and immutable packaged snapshot checks; no Android build."""
import hashlib,importlib.util,json,unittest
from pathlib import Path
ROOT=Path(__file__).parent
spec=importlib.util.spec_from_file_location('generator',ROOT/'generate-navigation-rules.py');generator=importlib.util.module_from_spec(spec);spec.loader.exec_module(generator)
class RulesTests(unittest.TestCase):
 def test_types_and_scope_are_not_broadened(self):
  blocks,allows=generator.collect(['||global.example^','||doc.example^$document','||all.example^$all','||scoped.example^$domain=site.example','||third.example^$third-party','||script.example^$script','||path.example/ad','||popup.example^$popup'])
  self.assertEqual(blocks,{'global.example','doc.example','all.example'});self.assertEqual(allows,set())
 def test_document_exceptions_and_resource_exceptions(self):
  _,allows=generator.collect(['@@||login.example/callback','@@||safe.example^$document','@@||ads.example^$script,xhr','@@||ads.example/clk;$popup,domain=foo.example'])
  self.assertEqual(allows,{'login.example','safe.example'})
 def test_snapshot(self):
  metadata=json.loads((ROOT/'navigation-rules-provenance.json').read_text('utf8'));data=(ROOT/'chromext/app/src/main/assets/banana-navigation-hosts.txt').read_bytes();self.assertEqual(hashlib.sha256(data).hexdigest(),metadata['asset_sha256']);lines=data.decode().splitlines();self.assertEqual(sum(x.startswith('+') for x in lines),metadata['block_hosts']);self.assertEqual(sum(x.startswith('-') for x in lines),metadata['exception_hosts']);self.assertGreater(metadata['block_hosts'],80000)
if __name__=='__main__':unittest.main()
