import React, { useState } from 'react';
import { TextInput, View } from 'react-native';
import { NO_BUNDLE } from '../store/defaults';
import { useStore } from '../store/store';
import type { Preset } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Chip, Group, Icon, Row, Section, T } from '../ui/primitives';
import { Sheet, useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { BUNDLE_ITEMS } from '../logic/features';
import { parseDpi } from '../logic/dpi';
import { Header } from './common';

const MAX_QUICK = 6;

export function Presets() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast();
  const [edit, setEdit] = useState<Preset | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [q, setQ] = useState('');
  const [qErr, setQErr] = useState('');
  const count = (p: Preset) => Object.values(p.bundle).filter(Boolean).length;

  const open = (p: Preset | null) => { setIsNew(!p); setEdit(p ? { ...p, bundle: { ...p.bundle } } : { id: `p${Date.now()}`, name: '', bundle: { ...NO_BUNDLE } }); };
  const save = () => {
    if (!edit || !edit.name.trim()) return;
    update((s) => ({ ...s, presets: isNew ? [...s.presets, { ...edit, name: edit.name.trim() }] : s.presets.map((p) => (p.id === edit.id ? { ...edit, name: edit.name.trim() } : p)) }));
    setEdit(null);
  };
  const remove = () => {
    if (!edit) return;
    const gone = edit;
    // profiles linked to this preset keep its settings as a custom bundle
    update((s) => ({ ...s, presets: s.presets.filter((p) => p.id !== gone.id), profiles: s.profiles.map((p) => (p.presetId === gone.id ? { ...p, presetId: 'custom', bundle: gone.bundle } : p)) }));
    setEdit(null); toast({ title: 'Preset deleted', sub: gone.name });
  };
  const addQuick = () => {
    const r = parseDpi(q);
    if (r.err !== undefined) { setQErr(r.err); return; }
    if (state.quick.includes(r.v)) { setQErr('Already in the list.'); return; }
    update((s) => ({ ...s, quick: [...s.quick, r.v].sort((a, b) => a - b) })); setQ(''); setQErr('');
  };

  return (
    <Screen>
      <Header title="Presets" sub="Gaming bundles and quick values" onBack={nav.back} />
      <Section title="Gaming presets" />
      <Group>
        {state.presets.map((p) => <Row key={p.id} leadIcon="tune" title={p.name} sub={`${count(p)} of ${BUNDLE_ITEMS.length} settings on`} onPress={() => open(p)} right={<Icon name="chevron-right" />} />)}
        <Row leadIcon="add" title={<T c={t.c.ac}>New preset</T>} onPress={() => open(null)} />
      </Group>
      <Section title="Quick DPI values" />
      <View style={{ borderRadius: t.r, borderWidth: t.bw, borderColor: t.c.bd, backgroundColor: t.c.sf, padding: 16 }}>
        <T v="sub" style={{ marginBottom: 12 }}>Shown on every profile and used by the Quick Settings tile. Tap one to remove it.</T>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8 }}>
          {state.quick.map((v) => <Chip key={v} h={40} label={`${v}  ✕`} on={false} onPress={() => state.quick.length > 2 ? update((s) => ({ ...s, quick: s.quick.filter((x) => x !== v) })) : toast({ title: 'Keep at least two values' })} />)}
        </View>
        {state.quick.length < MAX_QUICK && (
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
            <View style={{ flex: 1, height: 46, borderRadius: t.rs, borderWidth: 1.5, borderColor: qErr ? t.c.wn : t.c.bd2, paddingHorizontal: 14, justifyContent: 'center' }}>
              <TextInput value={q} onChangeText={(s) => { setQ(s.replace(/\D/g, '').slice(0, 4)); setQErr(''); }} keyboardType="number-pad" placeholder="Add a value" placeholderTextColor={t.c.mu} onSubmitEditing={addQuick} style={{ color: t.c.tx, fontSize: 16 }} />
            </View>
            <Btn label="Add" onPress={addQuick} />
          </View>
        )}
        {qErr ? <T v="sub" c={t.c.wn} style={{ marginTop: 8 }}>{qErr}</T> : null}
      </View>

      <Sheet visible={!!edit} onClose={() => setEdit(null)}>
        {edit && (<>
          <T v="h" style={{ marginBottom: 12 }}>{isNew ? 'New preset' : 'Edit preset'}</T>
          <View style={{ height: 50, borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, paddingHorizontal: 16, justifyContent: 'center', marginBottom: 12 }}>
            <TextInput value={edit.name} onChangeText={(s) => setEdit({ ...edit, name: s })} placeholder="Preset name" placeholderTextColor={t.c.mu} style={{ color: t.c.tx, fontSize: 17 }} />
          </View>
          <Group>{BUNDLE_ITEMS.map((k) => (
            <Row key={k.key} leadIcon={k.icon} title={k.title} minH={56} disabled={k.key === 'guard'} sub={k.key === 'guard' ? 'Coming in a later update' : undefined} right={<AppSwitch on={edit.bundle[k.key]} onChange={(v) => setEdit({ ...edit, bundle: { ...edit.bundle, [k.key]: v } })} />} />
          ))}</Group>
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 16 }}>
            {!isNew && <Btn v="tonal" label="Delete" onPress={remove} />}
            <View style={{ flex: 1 }}><Btn wide v="pri" label="Save preset" disabled={!edit.name.trim()} onPress={save} /></View>
          </View>
        </>)}
      </Sheet>
    </Screen>
  );
}
