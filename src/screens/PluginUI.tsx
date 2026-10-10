import React, { useRef } from 'react';
import { View } from 'react-native';
import { WebView, WebViewMessageEvent } from 'react-native-webview';
import { api } from '../native/api';
import { useStore } from '../store/store';
import { useNav } from '../nav';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { Header } from './common';

/** Runs in the page before it loads. Provides densify.exec and a KernelSU-style ksu.exec / ksu.toast. */
const BRIDGE = `
(function(){
  var n=0, cbs={};
  function post(m){ window.ReactNativeWebView.postMessage(JSON.stringify(m)); }
  window.__densifyDone=function(id,code,out,err){ var c=cbs[id]; delete cbs[id]; if(c) c(code,out,err); };
  window.densify={ exec:function(cmd){ return new Promise(function(res){ var id=++n; cbs[id]=function(c,o,e){ res({errno:c,stdout:o,stderr:e}); }; post({t:'exec',id:id,cmd:String(cmd)}); }); }, toast:function(m){ post({t:'toast',msg:String(m)}); } };
  window.ksu={ exec:function(cmd,opts,name){ var id=++n; cbs[id]=function(c,o,e){ if(name&&window[name]) window[name](c,o,e); }; post({t:'exec',id:id,cmd:String(cmd)}); }, toast:function(m){ post({t:'toast',msg:String(m)}); }, fullScreen:function(){}, moduleInfo:function(){ return '{}'; } };
  true;
})();`;

export function PluginUI({ id }: { id: string }) {
  const { state } = useStore();
  const nav = useNav(); const toast = useToast();
  const ref = useRef<WebView>(null);
  const p = state.plugins.find((x) => x.id === id);
  const html = `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;margin:16px;background:#fff;color:#111}button{padding:10px 14px;margin:4px 0}pre{white-space:pre-wrap}</style></head><body>${p?.webui ?? ''}</body></html>`;

  const onMessage = (e: WebViewMessageEvent) => {
    let m: { t: string; id?: number; cmd?: string; msg?: string };
    try { m = JSON.parse(e.nativeEvent.data); } catch { return; }
    if (m.t === 'toast') { toast({ title: m.msg ?? '' }); return; }
    if (m.t === 'exec' && m.id != null && m.cmd) {
      const done = (c: number, o: string, er: string) => ref.current?.injectJavaScript(`window.__densifyDone(${m.id},${c},${JSON.stringify(o)},${JSON.stringify(er)});true;`);
      api.shell(m.cmd).then((o) => done(0, o, '')).catch((er: Error) => done(1, '', er.message));
    }
  };

  return (
    <Screen scroll={false}>
      <Header title={p?.name ?? 'Plugin'} sub="WebUI" onBack={nav.back} />
      <View style={{ flex: 1, borderRadius: 12, overflow: 'hidden' }}>
        {p ? <WebView ref={ref} originWhitelist={['about:*']} source={{ html, baseUrl: 'about:blank' }} injectedJavaScriptBeforeContentLoaded={BRIDGE} onMessage={onMessage} javaScriptEnabled domStorageEnabled={false} allowFileAccess={false} /> : null}
      </View>
    </Screen>
  );
}
