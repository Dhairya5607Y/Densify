import React, { useState } from 'react';
import { Pressable, TextInput, View } from 'react-native';
import { useTheme } from '../theme/ThemeContext';
import { AppIcon, Chip, Group, Icon, Row, T } from '../ui/primitives';
import { Sheet } from '../ui/overlays';
import { useStore } from '../store/store';
import { useNav } from '../nav';
import { nativeAvailable, InstalledApp } from '../native/api';
import { useApps } from '../logic/hooks';
import { Ionicons } from '@expo/vector-icons';

export function Header({ title, sub, onBack, right }: { title: string; sub?: string; onBack?: () => void; right?: React.ReactNode }) {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 16 }}>
      {onBack ? <Pressable onPress={onBack} style={{ width: 44, height: 44, borderRadius: t.surface === 'hud' ? 4 : 22, alignItems: 'center', justifyContent: 'center', marginLeft: -8 }}><Icon name="arrow-back" size={24} color={t.c.tx} /></Pressable> : null}
      <View style={{ flex: 1 }}>
        <T v={onBack ? 'h' : 'title'}>{title}</T>
        {sub ? <T v="sub" style={{ marginTop: 2 }}>{sub}</T> : null}
      </View>
      {right}
    </View>
  );
}

export function SearchBar({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  const t = useTheme();
  return (
    <View style={{ height: 50, borderRadius: Math.min(t.rb, 25), borderWidth: 1, borderColor: t.c.bd, backgroundColor: t.c.sf, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, gap: 10 }}>
      <Ionicons name="search" size={20} color={t.c.mu} />
      <TextInput value={value} onChangeText={onChange} placeholder={placeholder} placeholderTextColor={t.c.mu} style={{ flex: 1, color: t.c.tx, fontSize: 15.5, fontFamily: t.mono ? 'monospace' : undefined }} />
    </View>
  );
}

export const Empty = ({ text }: { text: string }) => <View style={{ padding: 32, alignItems: 'center' }}><T v="sub" style={{ textAlign: 'center', fontSize: 14 }}>{text}</T></View>;

export function appFilter(apps: InstalledApp[], f: 'games' | 'all' | 'cfg', q: string, cfg: Set<string>) {
  const ql = q.toLowerCase();
  return apps.filter((a) => (f === 'games' ? a.isGame || cfg.has(a.pkg) : f === 'cfg' ? cfg.has(a.pkg) : true) && (a.name + a.pkg).toLowerCase().includes(ql))
    .sort((x, y) => Number(cfg.has(y.pkg)) - Number(cfg.has(x.pkg)) || x.name.localeCompare(y.name));
}
export const emptyApps = (f: string) => (!nativeAvailable ? 'Your installed apps appear here in the Densify app.' : f === 'games' ? 'No games found. Try “All apps”.' : 'Nothing found.');

/** Add-a-game bottom sheet: installed apps only. */
export function AddGameSheet({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const { state } = useStore();
  const nav = useNav();
  const { apps } = useApps();
  const [q, setQ] = useState('');
  const [f, setF] = useState<'games' | 'all'>('games');
  const cfg = new Set(state.profiles.map((p) => p.pkg));
  const list = appFilter(apps, f, q, cfg).filter((a) => !cfg.has(a.pkg));
  return (
    <Sheet visible={visible} onClose={onClose}>
      <T v="h">Add a game</T>
      <T v="sub" style={{ marginTop: 3, marginBottom: 14 }}>Choose an installed game to create a profile.</T>
      <SearchBar value={q} onChange={setQ} placeholder="Search installed apps…" />
      <View style={{ flexDirection: 'row', gap: 8, marginVertical: 12 }}>
        <Chip h={38} label="Games" on={f === 'games'} onPress={() => setF('games')} /><Chip h={38} label="All apps" on={f === 'all'} onPress={() => setF('all')} />
      </View>
      <View style={{ maxHeight: 360 }}>
        {list.length === 0 ? <Empty text={emptyApps(f)} /> : (
          <Group>{list.slice(0, 60).map((a) => (
            <Row key={a.pkg} lead={<AppIcon pkg={a.pkg} name={a.name} />} title={a.name} sub={a.pkg} onPress={() => { onClose(); nav.push({ name: 'profile', pkg: a.pkg, label: a.name }); }} right={<Icon name="chevron-right" />} />
          ))}</Group>
        )}
      </View>
    </Sheet>
  );
}
