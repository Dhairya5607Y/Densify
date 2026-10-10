import React, { useEffect, useState } from 'react';
import { Share, TextInput, View } from 'react-native';
import { api } from '../native/api';
import { shq } from '../logic/axplugin';
import { useStore } from '../store/store';
import { DEFAULT_STATE } from '../store/defaults';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { Btn, Group, Row, Section, Surface, T } from '../ui/primitives';
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
  const [out, setOut] = useState('');
  const [busy, setBusy] = useState(false);
  const [cwd, setCwd] = useState('/data/local/tmp');
  const [logs, setLogs] = useState<string[]>([]);
  const [restoreText, setRestoreText] = useState('');
  useEffect(() => { const read = () => setLogs(api.logs().slice(-60).reverse()); read(); const id = setInterval(read, 2000); return () => clearInterval(id); }, []);

  const run = async () => {
    const c = cmd.trim();
    if (!c || busy) return;
    if (!(await ensure('shell'))) return;
    setBusy(true);
    try {
      const raw = await api.shell(`cd ${shq(cwd)} 2>/dev/null; ${c}\necho __DENSIFY_PWD__$(pwd)`);
      const i = raw.lastIndexOf('__DENSIFY_PWD__');
      if (i >= 0) { setCwd(raw.slice(i + 15).trim() || cwd); setOut(raw.slice(0, i).trimEnd() || '(no output)'); } else setOut(raw || '(no output)');
    } catch (e) { setOut(`Error: ${(e as Error).message}`); }
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
        <TextInput value={cmd} onChangeText={setCmd} onSubmitEditing={run} placeholder="wm size" placeholderTextColor={t.c.mu} autoCapitalize="none" autoCorrect={false} style={[box, { height: 46 }]} />
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
          <View style={{ flex: 1 }}><Btn wide v="pri" label={busy ? 'Running…' : 'Run'} disabled={busy} onPress={run} /></View>
          <Btn v="tonal" label="Clear" onPress={() => setOut('')} />
        </View>
        {out ? <T v="sub" c={t.c.tx2} style={{ marginTop: 12, fontFamily: 'monospace', fontSize: 12.5 }}>{out}</T> : null}
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
