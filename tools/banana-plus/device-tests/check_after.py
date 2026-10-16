from native_support import *
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  data=b'<!doctype html><h1>Banana native module audit</h1>';self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
server=ThreadingHTTPServer(('127.0.0.1',18724),Handler);Thread(target=server.serve_forever,daemon=True).start();t=None;report={}
try:
 adb('forward','tcp:18610','localabstract:chrome_devtools_remote');adb('reverse','tcp:18724','tcp:18724')
 try:before={x['id'] for x in targets()}
 except Exception:before=set()
 adb('shell','am','start','-a','android.intent.action.VIEW','-d','http://127.0.0.1:18724/native-baseline','-p',PACKAGE);time.sleep(3)
 target=None
 for attempt in range(30):
  try:target=next((x for x in targets() if x['id'] not in before and x.get('url','').startswith('http://127.0.0.1:18724/')),None)
  except Exception:pass
  if target:break
  time.sleep(.5)
 assert target,'No owned test target after cold start'
 t=Tab(target);open_manager(t)
 scripts=manager_call(t,{'op':'list'});guard=manager_call(t,{'op':'navigationGuard'});report={'package':package_state(),'scripts':[{k:x.get(k) for k in ('id','version','enabled')} for x in scripts], 'guard':guard};old=json.loads((OUT/'native-module-before.json').read_text());report['data_identity_preserved']=all(report['package'][k]==old['package'][k] for k in ('uid','firstInstallTime','dataDir'));report['all_scripts_preserved']=report['scripts']==old['scripts'];assert guard['ready'] and guard['hooks']==3 and guard['version']=='1.0.1','Native guard did not initialize'
 print(json.dumps({'package':report['package'],'scripts':len(scripts),'guard':guard,'data_identity_preserved':report['data_identity_preserved'],'all_scripts_preserved':report['all_scripts_preserved']}),flush=True)
finally:
 if t:close_owned(t)
 adb('forward','--remove','tcp:18610');adb('reverse','--remove','tcp:18724');server.shutdown();server.server_close();(OUT/'native-module-after.json').write_text(json.dumps(report,indent=2))
