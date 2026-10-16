import sys
from device_support import *
import subprocess,xml.etree.ElementTree as ET,re
ADB=os.environ.get('BANANA_ADB') or shutil.which('adb') or str(Path.home()/'AppData/Local/Android/sdk/platform-tools/adb.exe')
DEVICE_ARGS=['-s',os.environ['BANANA_DEVICE']] if os.environ.get('BANANA_DEVICE') else ['-d']
PACKAGE='org.triple.banana'
HOSTS.update({'127.0.0.1','localhost'})
def adb(*args):
 r=subprocess.run([ADB,*DEVICE_ARGS,*args],capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
 if r.returncode:raise RuntimeError('ADB failed: '+' '.join(args[:2]))
 return r.stdout

def package_state():
 text=adb('shell','dumpsys','package',PACKAGE).decode('utf8','replace');out={}
 for key,pattern in [('uid',r'userId=(\d+)'),('versionCode',r'versionCode=(\d+)'),('firstInstallTime',r'firstInstallTime=([^\r\n]+)'),('dataDir',r'dataDir=([^\r\n]+)')]:
  m=re.search(pattern,text);out[key]=m[1].strip() if m else None
 return out

def manager_call(t,data):
 expression="(async()=>{let token=location.pathname.split('/')[1];let r=await fetch(location.href+'api',{method:'POST',headers:{'Content-Type':'application/json','X-Banana-Manager':token},body:JSON.stringify(DATA)});return await r.json()})()".replace('DATA',json.dumps(data))
 r=t.call('Runtime.evaluate',{'expression':expression,'returnByValue':True,'awaitPromise':True},timeout=25).get('result',{}).get('value')
 if not r or not r.get('ok'):raise RuntimeError('Private manager operation failed')
 return r['value']

def open_manager(t):
 def saved():
  prefs=ET.fromstring(adb('shell','run-as',PACKAGE,'cat','shared_prefs/BananaScriptManager.xml'))
  address=next((n.text for n in prefs if n.get('name')=='_manager_last_url'),None)
  u=urlsplit(address or '')
  if u.hostname!='127.0.0.1' or not re.fullmatch(r'/[a-f0-9-]{72}/',u.path):raise RuntimeError('No valid manager restore address')
  return address
 t.call('Page.navigate',{'url':saved()})
 for _ in range(16):
  time.sleep(.4);address=saved();match=next((x for x in targets() if x['id']==t.id and x.get('url')==address),None)
  if match:return t
 raise RuntimeError('Private manager did not restore in owned test tab')

def close_owned(t):
 try:urllib.request.urlopen(BASE+'/json/close/'+t.id,timeout=4).read()
 except Exception:pass
 t.ws.close()
