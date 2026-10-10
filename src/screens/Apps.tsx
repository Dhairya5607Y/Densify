import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { api } from '../native/api';
import { useNav } from '../nav';
import { useTheme } from '../theme/ThemeContext';
import { AppIcon, Btn, Group, Row, Section, Surface, T } from '../ui/primitives';
import { Screen } from '../ui/scroll';
import { Header } from './common';

type Grants = Record<string, 'allow' | 'deny' | 'ask'>;
const read = (): Grants => { try { return JSON.parse(api.storageGet('apiGrants') ?? '{}'); } catch { return {}; } };

/** Other apps that asked to use Densify's access. Nothing runs for an app until you allow it here. */
export function Apps() {
  const t = useTheme(); const nav = useNav();
  const [g, setG] = useState<Grants>(read);
  useEffect(() => { const id = setInterval(() => setG(read()), 1500); return () => clearInterval(id); }, []);
  const set = (pkg: string, v: Grants[string] | null) => {
    const n = { ...read() };
    if (v) n[pkg] = v; else delete n[pkg];
    api.storageSet('apiGrants', JSON.stringify(n)); setG(n);
  };
  const list = Object.entries(g);
  const asking = list.filter(([, v]) => v === 'ask');
  const known = list.filter(([, v]) => v !== 'ask');
  const row = ([pkg, v]: [string, string]) => (
    <Row key={pkg} lead={<AppIcon pkg={pkg} name={pkg} size={44} />} title={pkg} sub={v === 'allow' ? 'Allowed' : v === 'deny' ? 'Denied' : 'Asking for access'} minH={72}
      right={v === 'ask'
        ? <View style={{ flexDirection: 'row', gap: 6 }}><Btn v="tonal" label="Deny" onPress={() => set(pkg, 'deny')} /><Btn v="pri" label="Allow" onPress={() => set(pkg, 'allow')} /></View>
        : <View style={{ flexDirection: 'row', gap: 6 }}><Btn v="tonal" label={v === 'allow' ? 'Revoke' : 'Allow'} onPress={() => set(pkg, v === 'allow' ? 'deny' : 'allow')} /><Btn v="text" label="Remove" onPress={() => set(pkg, null)} /></View>} />
  );
  return (
    <Screen>
      <Header title="Apps" sub="Who can use Densify's access" onBack={nav.back} />
      {list.length === 0 && <Surface pad={18}><T v="sub">No app has asked yet. An app asks by binding to Densify's service (see Developer). It then appears here so you can allow or deny it.</T></Surface>}
      {asking.length > 0 && (<><Section title="Waiting for you" /><Group>{asking.map(row)}</Group></>)}
      {known.length > 0 && (<><Section title="Decided" /><Group>{known.map(row)}</Group></>)}
      <T v="sub" c={t.c.mu} style={{ margin: 10 }}>An allowed app can run shell commands and change DPI through Densify. Only allow apps you trust.</T>
    </Screen>
  );
}
