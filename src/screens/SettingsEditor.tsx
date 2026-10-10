import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { TextInput, View } from 'react-native';
import { api } from '../native/api';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { Btn, Chip, Group, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

type NS = 'system' | 'secure' | 'global';

/** Browse and edit Android's system, secure and global settings tables. */
export function SettingsEditor() {
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const [ns, setNs] = useState<NS>('global');
  const [rows, setRows] = useState<[string, string][]>([]);
  const [q, setQ] = useState('');
  const [key, setKey] = useState('');
  const [val, setVal] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!(await ensure('developer'))) return;
    setBusy(true);
    try {
      const out = await api.shell(`settings list ${ns}`);
      setRows(out.split('\n').map((l) => { const i = l.indexOf('='); return i > 0 ? ([l.slice(0, i), l.slice(i + 1)] as [string, string]) : null; }).filter((x): x is [string, string] => !!x));
    } catch (e) { toast({ title: "Couldn't read settings", sub: (e as Error).message, err: true }); }
    setBusy(false);
  }, [ns, ensure, toast]);
  useEffect(() => { load(); }, [ns]);

  const shown = useMemo(() => { const s = q.trim().toLowerCase(); return (s ? rows.filter(([k, v]) => k.toLowerCase().includes(s) || v.toLowerCase().includes(s)) : rows).slice(0, 80); }, [rows, q]);
  const safe = (s: string) => /^[\w.:-]+$/.test(s);
  const put = async () => {
    if (!safe(key) || !val.trim()) { toast({ title: 'Enter a key and a value', sub: 'Keys may only use letters, digits . _ : -', err: true }); return; }
    try { await api.shell(`settings put ${ns} ${key} '${val.replace(/'/g, "'\\''")}'`); toast({ title: `${key} saved` }); load(); }
    catch (e) { toast({ title: "Couldn't save", sub: (e as Error).message, err: true }); }
  };
  const del = async () => {
    if (!safe(key)) return;
    try { await api.shell(`settings delete ${ns} ${key}`); toast({ title: `${key} deleted` }); setKey(''); setVal(''); load(); }
    catch (e) { toast({ title: "Couldn't delete", sub: (e as Error).message, err: true }); }
  };
  const input = { height: 46, borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, color: t.c.tx, paddingHorizontal: 14, fontSize: 14, fontFamily: 'monospace' } as const;

  return (
    <Screen>
      <Header title="Settings editor" sub="Android system, secure and global tables" onBack={nav.back} />
      <View style={{ flexDirection: 'row', gap: 8, marginBottom: 12 }}>
        {(['system', 'secure', 'global'] as const).map((n) => <Chip key={n} h={40} label={n} on={ns === n} onPress={() => setNs(n)} />)}
      </View>
      <Surface pad={14}>
        <TextInput value={key} onChangeText={setKey} placeholder="key" placeholderTextColor={t.c.mu} autoCapitalize="none" autoCorrect={false} style={input} />
        <TextInput value={val} onChangeText={setVal} placeholder="value" placeholderTextColor={t.c.mu} autoCapitalize="none" autoCorrect={false} style={[input, { marginTop: 8 }]} />
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
          <View style={{ flex: 1 }}><Btn wide v="pri" label="Save" onPress={put} /></View>
          <Btn v="tonal" label="Delete" onPress={del} />
        </View>
      </Surface>
      <T v="sub" style={{ margin: 10 }}>Changing the wrong value can break your phone. Note the old value before you edit it.</T>
      <Section title={busy ? 'Loading…' : `${rows.length} entries`} />
      <TextInput value={q} onChangeText={setQ} placeholder="Search" placeholderTextColor={t.c.mu} autoCapitalize="none" style={[input, { marginBottom: 10 }]} />
      <Group>
        {shown.map(([k, v]) => <Row key={k} title={k} sub={v.slice(0, 120)} minH={56} onPress={() => { setKey(k); setVal(v); }} />)}
      </Group>
    </Screen>
  );
}
