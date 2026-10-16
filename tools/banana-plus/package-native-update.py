"""Repackage an existing signed browser with a ready CI module; no compilation."""
import argparse,hashlib,json,os,shutil,struct,subprocess,zipfile
from pathlib import Path

def sha(path):
 with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--module',type=Path,required=True);p.add_argument('--base',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--signing-dir',type=Path,required=True);a=p.parse_args()
 a.output_dir.mkdir(parents=True,exist_ok=True)
 assert sha(a.base)=='776c6cce7c002c0c1b4532d708293c954c53da7ac427d428bfa46eb08de08e99','Base APK digest changed'
 sdk=Path.home()/'AppData/Local/Android/sdk/build-tools/36.0.0'
 def run(args,env=None):
  r=subprocess.run([str(x) for x in args],capture_output=True,text=True,encoding='utf8',errors='replace',env=env,creationflags=subprocess.CREATE_NO_WINDOW)
  if r.returncode:raise RuntimeError('Artifact tool failed: '+str(args[0])+' '+r.stderr[:600])
  return r.stdout
 raw=a.output_dir/'browser-unaligned.apk';aligned=a.output_dir/'browser-aligned.apk';final=a.output_dir/'Banana-Plus-native-guard-1.0.1-phone.apk'
 changes={};module_name='assets/lspatch/modules/org.matrix.chromext.apk'
 with zipfile.ZipFile(a.base) as src,zipfile.ZipFile(raw,'w',allowZip64=True) as dst:
  for entry in src.infolist():
   n=entry.filename
   if n=='stamp-cert-sha256' or (n.startswith('META-INF/') and n.upper().endswith(('.RSA','.DSA','.EC','.SF','.MF'))):continue
   # Rewriting all entries also removes LSPatch's linked-entry ZIP flag ambiguity.
   info=zipfile.ZipInfo(n,entry.date_time);info.compress_type=entry.compress_type;info.external_attr=entry.external_attr
   if n==module_name:dst.writestr(info,a.module.read_bytes());changes[n]=sha(a.module)
   else:
    with src.open(entry) as source,dst.open(info,'w') as target:shutil.copyfileobj(source,target,1024*1024)
 run([sdk/'zipalign.exe','-f','-P','16','4',raw,aligned]);shutil.copy2(aligned,final)
 env=os.environ.copy();env['BANANA_SIGN_PASSWORD']=(a.signing_dir/'password.txt').read_text('utf8').strip()
 run(['java','-jar',sdk/'lib/apksigner.jar','sign','--ks',a.signing_dir/'banana-local.p12','--ks-key-alias','banana-local','--ks-pass','env:BANANA_SIGN_PASSWORD','--key-pass','env:BANANA_SIGN_PASSWORD',final],env)
 signature=run(['java','-jar',sdk/'lib/apksigner.jar','verify','--verbose','--print-certs',final]);base_signature=run(['java','-jar',sdk/'lib/apksigner.jar','verify','--print-certs',a.base])
 def cert(s):return next(x.strip() for x in s.splitlines() if 'certificate SHA-256 digest:' in x)
 assert cert(signature)==cert(base_signature),'Update signer differs'
 run([sdk/'zipalign.exe','-c','-P','16','4',final])
 count=0
 with zipfile.ZipFile(a.base) as src,zipfile.ZipFile(final) as dst:
  for entry in src.infolist():
   n=entry.filename
   if n in changes or n=='stamp-cert-sha256' or (n.startswith('META-INF/') and n.upper().endswith(('.RSA','.DSA','.EC','.SF','.MF'))):continue
   def digest(z,e):
    with z.open(e) as f:return hashlib.file_digest(f,'sha256').hexdigest()
   assert digest(src,entry)==digest(dst,n),n;count+=1
  assert dst.read(module_name)==a.module.read_bytes()
  assert dst.read('AndroidManifest.xml')==src.read('AndroidManifest.xml')
 report={'base_sha256':sha(a.base),'module_sha256':sha(a.module),'apk_sha256':sha(final),'size':final.stat().st_size,'package':'org.triple.banana','versionCode':520151463,'browser_version_pinned_reason':'Chromium compiled version and LSPatch original classpath must stay aligned; module has its own incremented version','module_version':'3.8.3-bananaplus.2','native_guard_version':'1.0.1','changed_entries':changes,'unchanged_entries_verified':count,'signer_matches_installed_base':True,'apk_signature_verified':True,'zipalign_16k_verified':True,'compiled_locally':False,'runtime_verification':'pending'}
 (a.output_dir/'packaging-verification.json').write_text(json.dumps(report,indent=2)+'\n');(a.output_dir/'signature-verification.txt').write_text(signature);print(json.dumps(report),flush=True)
if __name__=='__main__':main()
