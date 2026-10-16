from native_support import *
from device_support import Monitor
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from threading import Thread
from urllib.parse import parse_qs,quote
LOCAL='http://127.0.0.1:18724';OTHER='http://localhost:18724';AD='http://s.pemsrv.com:18724/ad'
HITS=[];ROWS=[];owned=set();t=None
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  u=urlsplit(self.path);q=parse_qs(u.query);HITS.append(u.path)
  if u.path=='/jump':
   if q.get('delay'):time.sleep(float(q['delay'][0]))
   self.send_response(int(q.get('code',['302'])[0]));self.send_header('Location',q.get('to',[AD])[0]);self.send_header('Cache-Control','no-store');self.end_headers();return
  if u.path=='/source':
   href=q.get('href',['/wanted'])[0];blank=' target="_blank"' if q.get('blank') else '';body='<a id="action" style="display:block;padding:30px;background:#ddd" href="'+href.replace('&','&amp;').replace('"','&quot;')+'"'+blank+'>Requested link</a>'
  else:body='<h1>Requested page</h1>'
  data=('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">'+body+'<script>window.auditMarker=Math.random().toString(36);</script>').encode();self.send_response(200);self.send_header('Content-Type','text/html');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
class OwnMonitor(Monitor):
 def __init__(self,target):self.closed_ids=set();super().__init__(target)
 def event(self,item):
  ident=item.get('params',{}).get('targetInfo',{}).get('targetId')
  if ident not in self.closed_ids:super().event(item)
 def close_created(self):
  self.closed_ids.update(self.created);super().close_created()
def jump(to,code=302,delay=None):return LOCAL+'/jump?code='+str(code)+'&to='+quote(to,safe='')+('&delay='+str(delay) if delay else '')
def source(href,blank=False):return LOCAL+'/source?href='+quote(href,safe='')+('&blank=1' if blank else '')
def touch(t):
 point=t.js('(()=>{let r=action.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()');assert point
 t.call('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[point]});t.call('Input.dispatchTouchEvent',{'type':'touchEnd','touchPoints':[]})
def state(t):open_manager(t);return manager_call(t,{'op':'navigationGuard'})
def connect_new(address):
 try:before={x['id'] for x in targets()}
 except Exception:before=set()
 adb('shell','am','start','-a','android.intent.action.VIEW','-d',address,'-p',PACKAGE)
 until=time.monotonic()+20
 while time.monotonic()<until:
  try:
   target=next((x for x in targets() if x['id'] not in before and x.get('url','').startswith(LOCAL)),None)
   if target:owned.add(target['id']);result=OwnMonitor(target);result.call('Page.bringToFront');return result
  except Exception:pass
  time.sleep(.5)
 raise RuntimeError('Owned test tab failed to start')
def save(row):ROWS.append(row);(OUT/'native-redirect-installed-audit.json').write_text(json.dumps(ROWS,indent=2));print(json.dumps(row),flush=True)
server=ThreadingHTTPServer(('127.0.0.1',18724),Handler);Thread(target=server.serve_forever,daemon=True).start()
try:
 adb('forward','tcp:18610','localabstract:chrome_devtools_remote');adb('reverse','tcp:18724','tcp:18724');t=connect_new(source('/wanted'));initial=state(t);assert initial['ready'] and initial['hooks']==3
 cases=[('block_'+str(c),jump(AD,c),True,False) for c in [301,302,303,307,308]]+[
 ('block_chain',jump(jump(AD)),True,False),('block_scheme_relative',jump('//s.pemsrv.com:18724/ad'),True,False),('block_https_host',jump('https://enrtx.com/ad'),True,False),('block_canonical_host',jump('http://S.PEMSRV.COM.:18724/ad'),True,False),('block_new_tab',jump(AD),True,True),('allow_relative',jump('/wanted'),False,False),('allow_cross_origin',jump(OTHER+'/wanted'),False,False),('allow_chain',jump(jump('/wanted')),False,False),('allow_ad_name_in_path',jump('/wanted?pemsrv.com'),False,False),('allow_new_tab',jump(OTHER+'/wanted'),False,True)]
 before_status=initial
 for name,href,expect_block,blank in cases:
  t.close_created();t.reset();url=source(href,blank);t.call('Page.navigate',{'url':url});t.wait(.7);assert t.js('window.__bananaClickGuard?.version')=='2.1.3'
  t.reset();t.refresh_frames();start=len(HITS);marker=t.js('window.auditMarker');touch(t);t.wait(1.5);current=urlsplit(t.root().get('url',''));same_document=marker is not None and marker==t.js('window.auditMarker');ad_responses=[e for e in t.events if e.get('event')=='response' and e.get('type')=='Document' and e.get('url',{}).get('host') in {'s.pemsrv.com','enrtx.com'}];aux=list(t.aux);children=len(t.created);ad_open=any(e.get('url',{}).get('host') in {'s.pemsrv.com','enrtx.com'} for e in aux if e.get('url',{}).get('scheme') in {'http','https'})
  # A canceled request can appear as a pending target URL. Commit is checked separately below.
  final_hosts=[]
  for ident in t.created:
   target=next((x for x in targets() if x['id']==ident),None)
   if target:final_hosts.append(urlsplit(target.get('url','')).hostname)
  new_status=state(t);delta=new_status['blockedServerRedirects']-before_status['blockedServerRedirects'];before_status=new_status
  if expect_block:ok=delta>=1 and same_document and not ad_responses and current.path=='/source' and all(x not in {'s.pemsrv.com','enrtx.com'} for x in final_hosts)
  elif blank:ok=delta==0 and current.path=='/source' and children==1 and '/wanted' in HITS[start:]
  else:ok=delta==0 and current.path=='/wanted'
  save({'case':name,'passed':ok,'native_server_blocks':delta,'source_preserved':current.path=='/source','current_host':current.hostname,'current_path':current.path,'requested_new_tab':blank,'new_tabs':children,'new_tab_final_hosts':final_hosts,'original_document_unchanged':same_document,'advertiser_document_responses':len(ad_responses),'page_world_interception_used':False,'cdp_fetch_interception_used':False})
  t.close_created()
 # No CDP connection exists when the server sends its delayed redirect.
 t.call('Page.navigate',{'url':source(jump(AD,302,2))});t.wait(.5);touch(t);ident=t.id;t.ws.close();adb('forward','--remove','tcp:18610');time.sleep(3);adb('forward','tcp:18610','localabstract:chrome_devtools_remote');target=next(x for x in targets() if x['id']==ident);t=OwnMonitor(target);current=urlsplit(t.root().get('url',''));now=state(t);delta=now['blockedServerRedirects']-before_status['blockedServerRedirects'];save({'case':'without_debugger_connection','passed':current.path=='/source' and delta>=1,'native_server_blocks':delta,'source_preserved':current.path=='/source'});close_owned(t);owned.discard(t.id);t=None
 # Three complete browser-process restarts; the guard must install before first navigation.
 for index in range(1,4):
  adb('shell','am','force-stop',PACKAGE);t=connect_new(source(jump(AD)));t.wait(.5);touch(t);t.wait(1.5);current=urlsplit(t.root().get('url',''));status=state(t);save({'case':'cold_start_'+str(index),'passed':status['ready'] and status['hooks']==3 and status['blockedServerRedirects']>=1 and current.path=='/source','guard_ready':status['ready'],'native_server_blocks':status['blockedServerRedirects'],'source_preserved':current.path=='/source'});close_owned(t);owned.discard(t.id);t=None
finally:
 if t:
  try:t.close_created();t.call('Target.setDiscoverTargets',{'discover':False});close_owned(t);owned.discard(t.id)
  except Exception:pass
 for ident in list(owned):
  try:urllib.request.urlopen(BASE+'/json/close/'+ident,timeout=4).read();owned.discard(ident)
  except Exception:pass
 cleanup={}
 for key,args in [('forward_removed',('forward','--remove','tcp:18610')),('reverse_removed',('reverse','--remove','tcp:18724'))]:
  try:adb(*args);cleanup[key]=True
  except Exception:cleanup[key]=False
 cleanup['owned_test_tabs_closed']=not owned;server.shutdown();server.server_close();(OUT/'native-redirect-installed-cleanup.json').write_text(json.dumps(cleanup,indent=2));print('COMPLETE',len(ROWS),cleanup,flush=True)
