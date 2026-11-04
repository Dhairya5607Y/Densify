import React, { useEffect, useState } from 'react';
import { Share, TextInput, View } from 'react-native';
import { api } from '../native/api';
import { useStore } from '../store/store';
import { DEFAULT_STATE } from '../store/defaults';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { Btn, Chip, Group, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const FAQ: [string, string][] = [
  ['Connection drops after a while', 'Turn on "Disable adb authorization timeout" in Developer options, and keep Reconnect automatically on in Settings.'],
  ['Densify stops in the background', 'Set battery to Unrestricted for Densify. On Xiaomi, Oppo, Vivo, Huawei and Samsung also allow Auto-start and lock the app in Recents.'],
  ['Pairing notification does not appear', 'Allow notifications, then open Wireless Debugging > Pair device with pairing code and reply from the notification.'],
  ['Screen looks wrong after stretch', 'Open the floating panel or notification and tap Restore. This resets size and DPI together.'],
  ['Floating panel does not show', 'Allow display over other apps, set Floating panel to In games, and make sure monitoring is on.'],
];

export function Tools() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const [cmd, setCmd] = useState('');
  const [hist, setHist] = useState<{ cmd: string; text: string; ok: boolean }[]>([]);
  const [busy, setBusy] = useState(false);
  const [cwd, setCwd] = useState('~');
  const [logs, setLogs] = useState<string[]>([]);
  const [restoreText, setRestoreText] = useState('');
  useEffect(() => {
    let sig = '';
    const read = () => { const l = api.logs().slice(-60).reverse(); const s = l.length + (l[0] ?? ''); if (s !== sig) { sig = s; setLogs(l); } };
    read();
    const id = setInterval(read, 2000);
    return () => clearInterval(id);
  }, []);

  const run = async (raw?: string) => {
    const c = (raw ?? cmd).trim();
    if (!c || busy) return;
    if (!(await ensure('shell'))) return;
    setBusy(true);
    let text = ''; let ok = true;
    try {
      text = (await api.session(c)).trim();
      // Commands like "wm size reset" print nothing when they work, so show the real state afterwards.
      if (/^\s*wm\s/.test(c)) text = [text, (await api.session('wm size; wm density')).trim()].filter(Boolean).join('\n');
      if (/^\s*cd(\s|$)/.test(c)) { const wd = (await api.session('pwd')).trim(); if (wd) setCwd(wd); }
      if (!text) text = 'Done';
      ok = !/\[exit \d+\]\s*$/.test(text);
    } catch (e) { text = (e as Error).message; ok = false; }
    setHist((h) => [{ cmd: c, text, ok }, ...h].slice(0, 12));
    setCmd('');
    setBusy(false);
  };
  const exportAll = () => Share.share({ message: JSON.stringify({ app: 'densify', v: 1, profiles: state.profiles, presets: state.presets, scripts: state.scripts, quick: state.quick }) }).catch(() => {});
  const importAll = () => {
    try {
      const o = JSON.parse(restoreText);
      if (o.app !== 'densify') throw new Error('not a Densify backup');
      update((s) => ({ ...s, profiles: o.profiles ?? s.profiles, presets: o.presets ?? s.presets, scripts: o.scripts ?? s.scripts, quick: o.quick ?? s.quick }));
      setRestoreText(''); toast({ title: 'Backup restored' });
    } catch { toast({ title: "That isn't a Densify backup", err: true }); }
  };
  const box = { borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, color: t.c.tx, paddingHorizontal: 14, fontSize: 14, fontFamily: 'monospace' } as const;

  return (
    <Screen>
      <Header title="Tools" sub="Terminal, logs and backup" onBack={nav.back} />

      <Section title={`Terminal · ${cwd}`} />
      <Surface pad={14}>
        <TextInput value={cmd} onChangeText={setCmd} onSubmitEditing={() => run()} blurOnSubmit={false} returnKeyType="send" placeholder="wm size" placeholderTextColor={t.c.mu} autoCapitalize="none" autoCorrect={false} style={[box, { height: 46 }]} />
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
          {['wm size', 'wm size reset', 'wm density', 'wm density reset', 'dumpsys battery'].map((q) => <Chip key={q} h={34} label={q} on={false} onPress={() => run(q)} />)}
        </View>
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
          <View style={{ flex: 1 }}><Btn wide v="pri" label={busy ? 'Running…' : 'Run'} disabled={busy} onPress={() => run()} /></View>
          <Btn v="tonal" label="Clear" onPress={() => setHist([])} /><Btn v="tonal" label="Reset" onPress={() => { api.sessionReset(); setCwd('~'); setHist([{ cmd: 'reset', text: 'Shell reset', ok: true }]); }} />
        </View>
        {hist.map((h, i) => (
          <View key={i} style={{ marginTop: 12 }}>
            <T v="sub" c={t.c.ac} w="600" style={{ fontFamily: 'monospace', fontSize: 12.5 }}>{`$ ${h.cmd}`}</T>
            <T v="sub" c={h.ok ? t.c.tx2 : t.c.wn} style={{ fontFamily: 'monospace', fontSize: 12.5, marginTop: 2 }}>{h.text}</T>
          </View>
        ))}
      </Surface>

      <Section title="Activity log" action="Clear" onAction={() => { api.clearLogs(); setLogs([]); }} />
      <Surface pad={14}>
        {logs.length === 0 ? <T v="sub">Nothing yet.</T> : logs.map((l, i) => <T key={i} v="sub" c={t.c.tx2} style={{ fontFamily: 'monospace', fontSize: 12, marginBottom: 4 }}>{l}</T>)}
      </Surface>

      <Section title="Backup" />
      <Group>
        <Row leadIcon="ios-share" title="Export profiles, presets and scripts" sub="Share the backup as text" onPress={exportAll} />
      </Group>
      <Surface pad={14} style={{ marginTop: 10 }}>
        <TextInput value={restoreText} onChangeText={setRestoreText} placeholder="Paste a backup here" placeholderTextColor={t.c.mu} multiline autoCapitalize="none" autoCorrect={false} style={[box, { minHeight: 70, paddingTop: 12, textAlignVertical: 'top' }]} />
        <View style={{ marginTop: 10 }}><Btn wide v="tonal" label="Restore backup" disabled={!restoreText.trim()} onPress={importAll} /></View>
      </Surface>

      <Section title="Troubleshooting" />
      <Group>
        {FAQ.map(([q, a]) => <Row key={q} title={q} sub={a} minH={76} />)}
      </Group>
      <View style={{ marginTop: 14 }}><Btn v="text" label="Reset app data" onPress={() => { update(() => DEFAULT_STATE); toast({ title: 'App data reset' }); }} /></View>
    </Screen>
  );
}
