import React, { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { WebView, WebViewMessageEvent } from 'react-native-webview';
import { api } from '../native/api';
import { loadWebroot, shq } from '../logic/axplugin';
import { useStore } from '../store/store';
import { useNav } from '../nav';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { Header } from './common';

/** Runs in the page before it loads. Provides densify.exec and a KernelSU-style ksu.exec / ksu.toast. */
const BRIDGE = (info: string, pkgs: string) => `
(function(){
  var n=0, cbs={}; var PK=${pkgs};
  function post(m){ window.ReactNativeWebView.postMessage(JSON.stringify(m)); }
  window.__densifyDone=function(id,code,out,err){ var c=cbs[id]; delete cbs[id]; if(c) c(code,out,err); };
  window.densify={ exec:function(cmd){ return new Promise(function(res){ var id=++n; cbs[id]=function(c,o,e){ res({errno:c,stdout:o,stderr:e}); }; post({t:'exec',id:id,cmd:String(cmd)}); }); }, toast:function(m){ post({t:'toast',msg:String(m)}); } };
  window.ksu={ exec:function(cmd,opts,name){ var id=++n; cbs[id]=function(c,o,e){ if(name&&window[name]) window[name](c,o,e); }; var cwd=''; try{ cwd=(JSON.parse(opts||'{}')).cwd||''; }catch(x){} post({t:'exec',id:id,cmd:String(cmd),cwd:cwd}); }, toast:function(m){ post({t:'toast',msg:String(m)}); }, fullScreen:function(){}, enableInsets:function(){}, exit:function(){ post({t:'exit'}); }, moduleInfo:function(){ return ${JSON.stringify(info)}; },
    spawn:function(cmd,args,opts,name){ var id=++n; var parts=JSON.parse(args||'[]').map(function(a){ return "'"+String(a).split("'").join("'\\\\''")+"'"; }); var full=String(cmd)+' '+parts.join(' '); var cwd=''; try{ cwd=(JSON.parse(opts||'{}')).cwd||''; }catch(x){} cbs[id]=function(c,o,e){ var p=window[name]; if(!p) return; if(o && p.stdout && p.stdout.emit) p.stdout.emit('data',o); if(e && p.stderr && p.stderr.emit) p.stderr.emit('data',e); if(p.emit) p.emit('exit',c); }; post({t:'exec',id:id,cmd:full,cwd:cwd}); },
    listPackages:function(type){ var l=PK.filter(function(p){ return type==='system'?p.isSystem:type==='user'?!p.isSystem:true; }).map(function(p){ return p.packageName; }); return JSON.stringify(l); },
    getPackagesInfo:function(json){ var want=JSON.parse(json||'[]'); return JSON.stringify(PK.filter(function(p){ return want.indexOf(p.packageName)>=0; })); },
    getPackagesIcons:function(){ return '[]'; } };
  true;
})();`;

export function PluginUI({ id }: { id: string }) {
  const { state } = useStore();
  const nav = useNav(); const toast = useToast();
  const ref = useRef<WebView>(null);
  const p = state.plugins.find((x) => x.id === id);
  const [pkgs, setPkgs] = useState<string | null>(null);
  useEffect(() => { api.listPackages().then((l) => setPkgs(JSON.stringify(l))); }, []);
  const [page, setPage] = useState<string | null>(p?.webroot ? null : (p?.webui ?? ''));
  useEffect(() => { if (p?.webroot) loadWebroot(p).then(setPage).catch((e: Error) => setPage(`<p>Couldn't load the plugin page: ${e.message}</p>`)); }, [id]);
  const html = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;margin:16px;background:#fff;color:#111}button{padding:10px 14px;margin:4px 0}pre{white-space:pre-wrap}</style></head><body>${page ?? ''}</body></html>`;

  const onMessage = (e: WebViewMessageEvent) => {
    let m: { t: string; id?: number; cmd?: string; msg?: string; cwd?: string };
    try { m = JSON.parse(e.nativeEvent.data); } catch { return; }
    if (m.t === 'exit') { nav.back(); return; }
    if (m.t === 'toast') { toast({ title: m.msg ?? '' }); return; }
    if (m.t === 'exec' && m.id != null && m.cmd) {
      const done = (c: number, o: string, er: string) => ref.current?.injectJavaScript(`window.__densifyDone(${m.id},${c},${JSON.stringify(o)},${JSON.stringify(er)});true;`);
      api.shell(`cd ${shq(m.cwd || p?.dir || '/')} 2>/dev/null; ${m.cmd}`).then((o) => done(0, o, '')).catch((er: Error) => done(1, '', er.message));
    }
  };

  return (
    <Screen scroll={false}>
      <Header title={p?.name ?? 'Plugin'} sub="WebUI" onBack={nav.back} />
      <View style={{ flex: 1, borderRadius: 12, overflow: 'hidden' }}>
        {p && page !== null && pkgs !== null ? <WebView ref={ref} originWhitelist={['about:*']} source={{ html, baseUrl: 'about:blank' }} injectedJavaScriptBeforeContentLoaded={BRIDGE(JSON.stringify({ id: p.id, name: p.name, version: p.version, author: p.author, description: p.desc }), pkgs)} onMessage={onMessage} javaScriptEnabled domStorageEnabled={false} allowFileAccess={false} /> : null}
      </View>
    </Screen>
  );
}
