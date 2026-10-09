import React, { useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';
import { api, Capabilities, useAdb } from '../native/api';
import { NO_BUNDLE } from '../store/defaults';
import { useStore } from '../store/store';
import type { Bundle, Profile as P } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AnimatedNumber, AppIcon, AppSwitch, Btn, Chip, Group, Icon, Row, Section, Slider, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { hexA } from '../ui/TabBar';
import { BUNDLE_ITEMS } from '../logic/features';
import { useEnsure } from '../logic/permissions';
import { parseDpi, SLIDER_MAX, SLIDER_MIN } from '../logic/dpi';
import { Header } from './common';

export function Profile({ pkg, label }: { pkg: string; label: string }) {
  const { state, update, log } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const existing = state.profiles.find((p) => p.pkg === pkg);
  const [d, setD] = useState<P>(() => existing ?? { pkg, name: label, dpi: state.defaultDpi || state.quick[0] || 420, auto: true, restore: true, presetId: state.presets[0]?.id ?? 'custom', bundle: state.presets[0]?.bundle ?? NO_BUNDLE });
  const [text, setText] = useState(String(d.dpi));
  const [err, setErr] = useState('');
  const [caps, setCaps] = useState<Capabilities | null>(null);
  const [keep, setKeep] = useState<{ v: number; t: number } | null>(null);
  useEffect(() => { api.capabilities().then(setCaps); }, []);
  useEffect(() => {
    if (!keep) return;
    const id = setInterval(() => setKeep((k) => (k ? { ...k, t: k.t - 1 } : k)), 1000);
    return () => clearInterval(id);
  }, [keep?.v]);
  useEffect(() => { if (keep && keep.t <= 0) revert(); }, [keep?.t]);

  const eff: Bundle = d.presetId === 'custom' ? d.bundle : state.presets.find((p) => p.id === d.presetId)?.bundle ?? d.bundle;
  const dirty = JSON.stringify(d) !== JSON.stringify(existing ?? null);
  const setDpi = (v: number) => { setD((x) => ({ ...x, dpi: v })); setText(String(v)); setErr(''); };
  const onText = (s: string) => {
    const digits = s.replace(/\D/g, '').slice(0, 4); setText(digits);
    const r = parseDpi(digits);
    if (r.err !== undefined) setErr(r.err); else { setErr(''); setD((x) => ({ ...x, dpi: r.v })); }
  };
  const ratio = state.defaultDpi ? d.dpi / state.defaultDpi : 1;
  const hint = err || (ratio < 0.7 ? 'Low density: the interface will look large.' : ratio > 1.35 ? 'High density: text and buttons will look very small.' : '');

  const apply = async () => {
    const r = parseDpi(text);
    if (r.err !== undefined) { setErr(r.err); return; }
    if (!(await ensure('preview'))) return;
    try { await api.setDensity(r.v); setKeep({ v: r.v, t: 10 }); log(`Applied ${r.v} DPI`, 'Test from profile', pkg); }
    catch (e) { toast({ title: `Couldn't apply ${r.v} DPI.`, sub: (e as Error).message || 'Check your Wireless Debugging connection.', err: true, action: { label: 'Retry', run: apply } }); }
  };
  const revert = () => { setKeep(null); api.restoreDefault().then((v) => toast({ title: `Reverted to ${v} DPI` })).catch((e: Error) => toast({ title: "Couldn't revert", sub: e.message, err: true, action: { label: 'Retry', run: revert } })); };
  const toggle = async (k: keyof Bundle) => {
    const on = !eff[k];
    if (on && !(await ensure(k))) return;
    setD((x) => ({ ...x, presetId: 'custom', bundle: { ...eff, [k]: on } }));
  };
  const save = () => { update((s) => ({ ...s, profiles: [...s.profiles.filter((p) => p.pkg !== pkg), d] })); toast({ title: `${d.name} saved`, sub: `${d.dpi} DPI · auto switching ${d.auto ? 'on' : 'off'}` }); nav.back(); };
  const del = () => { const prev = existing!; update((s) => ({ ...s, profiles: s.profiles.filter((p) => p.pkg !== pkg) })); nav.back(); toast({ title: 'Profile deleted', sub: d.name, action: { label: 'Undo', run: () => update((s) => ({ ...s, profiles: [...s.profiles, prev] })) } }); };

  const rowFor = (k: (typeof BUNDLE_ITEMS)[number]) => {
    const soon = k.key === 'guard';
    const perfNo = k.key === 'perf' && adb.state === 'connected' && caps?.perf === false;
    const dis = soon || perfNo;
    const sub = soon ? 'Coming in a later update' : perfNo ? 'Not available on this device'
      : k.key === 'hz' ? `Locks the highest your screen supports${caps ? ` · ${caps.hz} Hz` : ''}`
      : ({ perf: "Uses your phone's own performance mode", dnd: 'Blocks heads-up banners and call pop-ups', rot: 'Keeps the current orientation', bright: 'Turns off auto-brightness while playing', awake: 'Stops the screen from timing out' } as Record<string, string>)[k.key];
    return <Row key={k.key} leadIcon={k.icon} title={k.title} sub={sub} disabled={dis} right={<AppSwitch on={!dis && eff[k.key]} disabled={dis} onChange={() => toggle(k.key)} />} />;
  };

  return (
    <Screen footer={(
      <View style={{ padding: 16, backgroundColor: hexA(t.c.bg, 0.94) }}>
        {keep && (
          <Surface style={{ marginBottom: 12 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', padding: 12, paddingLeft: 16, gap: 8 }}>
              <View style={{ flex: 1 }}><T w="600" style={{ fontSize: 14.5 }}>Keep {keep.v} DPI?</T><T v="sub">Reverting to {state.defaultDpi || 'default'} in {keep.t}s</T></View>
              <Btn v="text" label="Revert" onPress={revert} /><Btn v="pri" label="Keep" onPress={() => setKeep(null)} />
            </View>
            <View style={{ height: 3, width: `${Math.max(0, keep.t) * 10}%`, backgroundColor: t.c.ac }} />
          </Surface>
        )}
        <Btn wide v="pri" label={dirty ? 'Save changes' : 'Saved'} disabled={!dirty} onPress={save} />
      </View>
    )}>
      <Header title={d.name} sub="Game profile" onBack={nav.back} right={existing ? <Btn v="text" label="Delete" onPress={del} /> : undefined} />
      <Group><Row minH={84} lead={<AppIcon pkg={pkg} name={d.name} size={60} />} title={existing ? 'Profile active' : 'New profile'} sub={`${pkg}`} /></Group>

      <Section title="Display density" />
      <Surface pad={18}>
        <View style={{ alignItems: 'center' }}>
          <AnimatedNumber value={d.dpi} style={{ fontSize: 64, fontWeight: '500', letterSpacing: -3, color: t.c.tx, fontFamily: t.mono ? 'monospace' : undefined }} />
          <T v="sub" style={{ marginTop: 2 }}>DPI</T>
        </View>
        <Slider value={Math.min(SLIDER_MAX, Math.max(SLIDER_MIN, d.dpi))} min={SLIDER_MIN} max={SLIDER_MAX} onChange={setDpi} />
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><T v="sub">{SLIDER_MIN}</T><T v="sub">{SLIDER_MAX}</T></View>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
          <View style={{ flex: 1, height: 48, borderRadius: t.rs, borderWidth: 1.5, borderColor: err ? t.c.wn : t.c.bd2, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16 }}>
            <TextInput value={text} onChangeText={onText} keyboardType="number-pad" onSubmitEditing={apply} style={{ flex: 1, color: t.c.tx, fontSize: 18, fontWeight: '500', fontFamily: t.mono ? 'monospace' : undefined }} />
            <T v="sub">DPI</T>
          </View>
          <Btn v="pri" label="Apply" onPress={apply} />
        </View>
        <T v="sub" c={t.c.wn} style={{ marginTop: 8, marginHorizontal: 4, minHeight: 20 }}>{hint}</T>
        <View style={{ flexDirection: 'row', gap: 8, marginTop: 4 }}>
          {state.quick.map((v) => <View key={v} style={{ flex: 1 }}><Chip label={String(v)} on={d.dpi === v} onPress={() => setDpi(v)} h={46} /></View>)}
        </View>
      </Surface>

      <Section title="Gaming preset" action="Edit presets" onAction={() => nav.push({ name: 'presets' })} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        {state.presets.map((p) => <Chip key={p.id} h={38} label={p.name} on={d.presetId === p.id} onPress={() => setD((x) => ({ ...x, presetId: p.id, bundle: p.bundle }))} />)}
        <Chip h={38} label="Custom" on={d.presetId === 'custom'} onPress={() => setD((x) => ({ ...x, presetId: 'custom', bundle: eff }))} />
      </View>
      <Group>{BUNDLE_ITEMS.map(rowFor)}</Group>

      <Section title="Automation" />
      <Group>
        <Row title="Automatically apply" sub={`Switch to ${d.dpi} DPI when ${d.name} launches`} right={<AppSwitch on={d.auto} onChange={(v) => setD((x) => ({ ...x, auto: v }))} />} />
        <Row title="Restore everything" sub={`Return DPI and every setting above when ${d.name} closes`} right={<AppSwitch on={d.restore} onChange={(v) => setD((x) => ({ ...x, restore: v }))} />} />
      </Group>
    </Screen>
  );
}
