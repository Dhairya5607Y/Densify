import React, { useState } from 'react';
import { TextInput, View } from 'react-native';
import { useStore } from '../store/store';
import type { Script } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Chip, Group, Row, Section, Surface, T } from '../ui/primitives';
import { Screen } from '../ui/scroll';
import { Header } from './common';

const TRIGGERS: { k: Script['trigger']; label: string }[] = [{ k: 'start', label: 'Game starts' }, { k: 'exit', label: 'Game exits' }, { k: 'boot', label: 'Phone boots' }];

/** Small shell snippets that run through the same connection as DPI changes. */
export function Scripts() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav();
  const [edit, setEdit] = useState<Script | null>(null);
  const input = { borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, color: t.c.tx, paddingHorizontal: 14, fontSize: 15 } as const;

  const save = () => {
    if (!edit || !edit.code.trim()) return;
    const s = { ...edit, name: edit.name.trim() || 'Script' };
    update((x) => ({ ...x, scripts: x.scripts.some((y) => y.id === s.id) ? x.scripts.map((y) => (y.id === s.id ? s : y)) : [...x.scripts, s] }));
    setEdit(null);
  };

  if (edit) {
    return (
      <Screen>
        <Header title={state.scripts.some((y) => y.id === edit.id) ? 'Edit script' : 'New script'} onBack={() => setEdit(null)} />
        <Surface pad={14}>
          <TextInput value={edit.name} onChangeText={(v) => setEdit({ ...edit, name: v })} placeholder="Name" placeholderTextColor={t.c.mu} style={[input, { height: 46, marginBottom: 10 }]} />
          <TextInput value={edit.code} onChangeText={(v) => setEdit({ ...edit, code: v })} placeholder="settings put global window_animation_scale 0.5" placeholderTextColor={t.c.mu} multiline autoCapitalize="none" autoCorrect={false} style={[input, { minHeight: 110, paddingTop: 12, textAlignVertical: 'top', fontFamily: 'monospace' }]} />
          <T v="sub" style={{ marginTop: 12, marginBottom: 8 }}>Runs when</T>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
            {TRIGGERS.map((x) => <Chip key={x.k} h={40} label={x.label} on={edit.trigger === x.k} onPress={() => setEdit({ ...edit, trigger: x.k })} />)}
          </View>
          {edit.trigger !== 'boot' && (
            <>
              <T v="sub" style={{ marginTop: 12, marginBottom: 8 }}>Only for this game</T>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
                <Chip h={40} label="Any game" on={edit.pkg === ''} onPress={() => setEdit({ ...edit, pkg: '' })} />
                {state.profiles.map((p) => <Chip key={p.pkg} h={40} label={p.name} on={edit.pkg === p.pkg} onPress={() => setEdit({ ...edit, pkg: p.pkg })} />)}
              </View>
            </>
          )}
        </Surface>
        <View style={{ marginTop: 16 }}><Btn wide v="pri" label="Save script" disabled={!edit.code.trim()} onPress={save} /></View>
      </Screen>
    );
  }

  return (
    <Screen>
      <Header title="Automation scripts" sub="Shell commands that run on triggers" onBack={nav.back} right={<Btn v="pri" label="New" onPress={() => setEdit({ id: String(Date.now()), name: '', code: '', trigger: 'start', pkg: '', on: true })} />} />
      {state.scripts.length === 0 ? (
        <Surface pad={18}><T v="sub">No scripts yet. A script is one or more shell commands, for example turning animations down while you play. They run with the same access as DPI changes, so only add commands you understand.</T></Surface>
      ) : (
        <>
          <Section title="Your scripts" />
          <Group>
            {state.scripts.map((s) => (
              <Row key={s.id} leadIcon="code" title={s.name} sub={`${TRIGGERS.find((x) => x.k === s.trigger)?.label}${s.pkg ? ` · ${state.profiles.find((p) => p.pkg === s.pkg)?.name ?? s.pkg}` : ''}`}
                onPress={() => setEdit(s)}
                right={<AppSwitch on={s.on} onChange={(v) => update((x) => ({ ...x, scripts: x.scripts.map((y) => (y.id === s.id ? { ...y, on: v } : y)) }))} />} />
            ))}
          </Group>
          <View style={{ marginTop: 12 }}><Btn v="text" label="Delete all scripts" onPress={() => update((x) => ({ ...x, scripts: [] }))} /></View>
        </>
      )}
    </Screen>
  );
}
