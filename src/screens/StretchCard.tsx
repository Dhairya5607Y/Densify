import React, { useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';
import { api, useAdb } from '../native/api';
import { useStore } from '../store/store';
import { useTheme } from '../theme/ThemeContext';
import { Btn, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { useEnsure } from '../logic/permissions';
import { formatSize, parseSize } from '../logic/size';

/** Stretch resolution, front and centre: type width*height, apply it now, reset it any time. */
export function StretchCard() {
  const t = useTheme(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const { log } = useStore();
  const connected = adb.state === 'connected';
  const [text, setText] = useState(() => { const s = api.getStretch(); return formatSize(s.w, s.h); });
  const [now, setNow] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = () => api.readSize().then((z) => setNow(z.override ? `${z.override.replace('x', '*')} (screen is ${z.physW}*${z.physH})` : `${z.physW}*${z.physH} (normal)`)).catch(() => setNow(''));
  useEffect(() => { if (connected) refresh(); }, [connected]);

  const apply = async () => {
    const r = parseSize(text);
    if (r.err !== undefined) { setErr(r.err); return; }
    setErr('');
    if (!(await ensure('stretch'))) return;
    setBusy(true);
    try { await api.applyStretch(r.w, r.h); log(`Stretched screen to ${r.w}*${r.h}`, 'Manual'); toast({ title: `Screen set to ${r.w}*${r.h}` }); refresh(); }
    catch (e) { toast({ title: "Couldn't change the resolution", sub: (e as Error).message, err: true, action: { label: 'Retry', run: apply } }); }
    setBusy(false);
  };
  const reset = async () => {
    setBusy(true);
    try { await api.resetStretch(); log('Screen size reset', 'Manual'); toast({ title: 'Screen size reset' }); refresh(); }
    catch (e) { toast({ title: "Couldn't reset the size", sub: (e as Error).message, err: true, action: { label: 'Retry', run: reset } }); }
    setBusy(false);
  };
  const onText = (s: string) => { setText(s.replace(/[^0-9*xX×,:\s]/g, '').slice(0, 12)); setErr(''); const r = parseSize(s); if (r.err === undefined) api.saveStretch(r.w, r.h); };

  return (
    <Surface pad={16} style={{ marginTop: 12 }}>
      <T w="600" style={{ fontSize: 15.5 }}>Stretch resolution</T>
      <T v="sub" style={{ marginTop: 2 }}>{now ? `Now ${now}` : 'Type a size as width*height'}</T>
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
        <View style={{ flex: 1, height: 48, borderRadius: t.rs, borderWidth: 1.5, borderColor: err ? t.c.wn : t.c.bd2, justifyContent: 'center', paddingHorizontal: 16 }}>
          <TextInput value={text} onChangeText={onText} placeholder="2160*1080" placeholderTextColor={t.c.mu} keyboardType="numbers-and-punctuation" autoCorrect={false} onSubmitEditing={apply}
            style={{ color: t.c.tx, fontSize: 18, fontWeight: '500', fontFamily: t.mono ? 'monospace' : undefined }} />
        </View>
        <Btn v="pri" label="Stretch" disabled={!connected || busy} onPress={apply} />
        <Btn v="text" label="Reset" disabled={!connected || busy} onPress={reset} />
      </View>
      <T v="sub" c={err ? t.c.wn : t.c.mu} style={{ marginTop: 8, minHeight: 18 }}>{err || 'Also in the floating panel. Games can use it automatically from their profile. Restore always resets it.'}</T>
    </Surface>
  );
}
