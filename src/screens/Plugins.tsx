import React, { useState } from 'react';
import { TextInput, View } from 'react-native';
import { api } from '../native/api';
import { useStore } from '../store/store';
import type { Plugin } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Group, Icon, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const EXAMPLE = JSON.stringify({
  id: 'fast-animations', name: 'Fast animations', version: '1.0', author: 'you', desc: 'Speeds up system animations while gaming.',
  actions: [{ id: 'off', name: 'Turn animations off', code: 'settings put global animator_duration_scale 0' }, { id: 'on', name: 'Turn animations on', code: 'settings put global animator_duration_scale 1' }],
  hooks: [{ trigger: 'start', code: 'settings put global animator_duration_scale 0.5' }, { trigger: 'exit', code: 'settings put global animator_duration_scale 1' }],
  webui: '<h3>Hello</h3><button onclick="densify.exec(\'wm size\').then(r=>out.textContent=r.stdout)">Screen size</button><pre id="out"></pre>',
}, null, 2);

export function parsePlugin(raw: string): Plugin {
  const o = JSON.parse(raw);
  if (!o || typeof o !== 'object') throw new Error('Not a plugin');
  const id = String(o.id ?? '').trim().replace(/[^\w.-]/g, '-');
  if (!id || !o.name) throw new Error('A plugin needs an id and a name');
  const acts = Array.isArray(o.actions) ? o.actions : [];
  const hooks = Array.isArray(o.hooks) ? o.hooks : [];
  return {
    id, name: String(o.name), version: String(o.version ?? '1.0'), author: String(o.author ?? ''), desc: String(o.desc ?? ''), on: true,
    actions: acts.filter((a: { code?: string }) => a && a.code).map((a: { id?: string; name?: string; code: string }, i: number) => ({ id: String(a.id ?? i), name: String(a.name ?? `Action ${i + 1}`), code: String(a.code) })),
    hooks: hooks.filter((h: { code?: string; trigger?: string }) => h && h.code && ['start', 'exit', 'boot'].includes(String(h.trigger))).map((h: { trigger: 'start' | 'exit' | 'boot'; code: string; pkg?: string }) => ({ trigger: h.trigger, code: String(h.code), pkg: h.pkg })),
    webui: typeof o.webui === 'string' && o.webui ? o.webui : undefined,
  };
}

export function Plugins() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const [adding, setAdding] = useState(false);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const box = { borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, color: t.c.tx, paddingHorizontal: 14, fontSize: 13, fontFamily: 'monospace' } as const;

  const install = async () => {
    setBusy(true);
    try {
      let raw = text.trim();
      if (/^https:\/\//i.test(raw)) { const r = await fetch(raw); if (!r.ok) throw new Error(`Download failed (${r.status})`); raw = await r.text(); }
      const p = parsePlugin(raw);
      const upd = state.plugins.some((x) => x.id === p.id);
      update((s) => ({ ...s, plugins: upd ? s.plugins.map((x) => (x.id === p.id ? { ...p, on: x.on } : x)) : [...s.plugins, p] }));
      toast({ title: upd ? `${p.name} updated` : `${p.name} installed` });
      setText(''); setAdding(false);
    } catch (e) { toast({ title: "Couldn't install", sub: (e as Error).message, err: true }); }
    setBusy(false);
  };
  const run = async (p: Plugin, a: Plugin['actions'][number]) => {
    if (!(await ensure('shell'))) return;
    try { const out = await api.shell(a.code); toast({ title: `${a.name}: done`, sub: out.trim().slice(0, 80) || undefined }); }
    catch (e) { toast({ title: `${a.name} failed`, sub: (e as Error).message, err: true }); }
  };
  const remove = (p: Plugin) => { update((s) => ({ ...s, plugins: s.plugins.filter((x) => x.id !== p.id) })); toast({ title: `${p.name} removed`, action: { label: 'Undo', run: () => update((s) => ({ ...s, plugins: [...s.plugins, p] })) } }); };

  if (adding) {
    return (
      <Screen>
        <Header title="Install plugin" sub="Paste plugin JSON or an https link" onBack={() => setAdding(false)} />
        <Surface pad={14}>
          <TextInput value={text} onChangeText={setText} placeholder="https://… or { JSON }" placeholderTextColor={t.c.mu} multiline autoCapitalize="none" autoCorrect={false} style={[box, { minHeight: 180, paddingTop: 12, textAlignVertical: 'top' }]} />
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
            <View style={{ flex: 1 }}><Btn wide v="pri" label={busy ? 'Installing…' : 'Install'} disabled={busy || !text.trim()} onPress={install} /></View>
            <Btn v="tonal" label="Example" onPress={() => setText(EXAMPLE)} />
          </View>
        </Surface>
        <T v="sub" style={{ margin: 10 }}>Plugins run shell commands with the same access as DPI changes. Only install plugins you trust. A plugin can have actions you run by hand, hooks that run on game start, game exit or boot, and a WebUI page.</T>
      </Screen>
    );
  }

  return (
    <Screen>
      <Header title="Plugins" sub="Add-ons with actions, hooks and a WebUI" onBack={nav.back} right={<Btn v="pri" label="Install" onPress={() => setAdding(true)} />} />
      {state.plugins.length === 0 ? (
        <Surface pad={18}><T v="sub">No plugins installed. Tap Install, then Example to see the format.</T></Surface>
      ) : state.plugins.map((p) => (
        <View key={p.id} style={{ marginBottom: 14 }}>
          <Group>
            <Row leadIcon="extension" title={p.name} sub={`v${p.version}${p.author ? ` · ${p.author}` : ''}${p.desc ? `\n${p.desc}` : ''}`} minH={76}
              right={<AppSwitch on={p.on} onChange={(v) => update((s) => ({ ...s, plugins: s.plugins.map((x) => (x.id === p.id ? { ...x, on: v } : x)) }))} />} />
            {p.actions.map((a) => <Row key={a.id} leadIcon="play-arrow" title={a.name} sub="Run now" disabled={!p.on} onPress={() => p.on && run(p, a)} />)}
            {p.webui ? <Row leadIcon="web" title="Open WebUI" sub="The plugin's own page" disabled={!p.on} onPress={() => p.on && nav.push({ name: 'webui', id: p.id })} right={<Icon name="chevron-right" />} /> : null}
            <Row leadIcon="delete" title="Remove" sub={`${p.hooks.length} hook${p.hooks.length === 1 ? '' : 's'}`} onPress={() => remove(p)} />
          </Group>
        </View>
      ))}
    </Screen>
  );
}
