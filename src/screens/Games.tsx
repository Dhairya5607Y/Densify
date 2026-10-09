import React, { useState } from 'react';
import { View } from 'react-native';
import { useStore } from '../store/store';
import { useNav } from '../nav';
import { AppIcon, Chip, Group, Icon, Row, T } from '../ui/primitives';
import { Screen } from '../ui/scroll';
import { useApps } from '../logic/hooks';
import { appFilter, Empty, emptyApps, Header, SearchBar } from './common';

export function Games() {
  const { state } = useStore();
  const nav = useNav();
  const { apps, loading } = useApps();
  const [q, setQ] = useState('');
  const [f, setF] = useState<'games' | 'all' | 'cfg'>('games');
  const cfg = new Map(state.profiles.map((p) => [p.pkg, p]));
  const list = appFilter(apps, f, q, new Set(cfg.keys()));
  return (
    <Screen tab>
      <Header title="Games" sub="Manage your DPI profiles" />
      <SearchBar value={q} onChange={setQ} placeholder="Search games…" />
      <View style={{ flexDirection: 'row', gap: 8, marginVertical: 12 }}>
        <Chip h={38} label="Games" on={f === 'games'} onPress={() => setF('games')} />
        <Chip h={38} label="All apps" on={f === 'all'} onPress={() => setF('all')} />
        <Chip h={38} label="Configured" on={f === 'cfg'} onPress={() => setF('cfg')} />
      </View>
      <T v="sub" style={{ marginHorizontal: 10, marginBottom: 8 }}>{loading ? 'Reading installed apps…' : `${list.length} ${f === 'games' ? 'game' : 'app'}${list.length === 1 ? '' : 's'}`}</T>
      {list.length === 0 ? <Empty text={emptyApps(f)} /> : (
        <Group>{list.map((a) => {
          const p = cfg.get(a.pkg);
          return <Row key={a.pkg} lead={<AppIcon pkg={a.pkg} name={a.name} />} title={a.name} sub={p ? `${p.dpi} DPI · ${p.auto ? 'Active' : 'Auto off'}` : a.pkg} onPress={() => nav.push({ name: 'profile', pkg: a.pkg, label: a.name })} right={<Icon name="chevron-right" />} />;
        })}</Group>
      )}
      <T v="sub" style={{ textAlign: 'center', margin: 22, lineHeight: 19 }}>Densify reads your installed apps and marks games using Android's app category. If a game is missing, choose All apps.</T>
    </Screen>
  );
}
