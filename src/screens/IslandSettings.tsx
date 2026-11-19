import React, { useRef, useState } from 'react';
import { PanResponder, Pressable, Text, TextInput, View } from 'react-native';
import { api } from '../native/api';
import { useStore } from '../store/store';
import { useTheme } from '../theme/ThemeContext';
import { AppSwitch, Btn, Chip, Icon, Slider, T, tap } from '../ui/primitives';

const SWATCHES = ['#000000', '#1c1c1e', '#0b1f3a', '#8cc4f5', '#ffffff', '#ff453a', '#30d158', '#bf5af2'];
const HEX = /^#?[0-9a-fA-F]{6}$/;
const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));
const isLight = (hex: string) => {
  const n = parseInt(hex.slice(1), 16);
  return (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255 > 0.62;
};

/** A to-scale sketch of the phone with the island on it. Drag the island to place it. */
function PhoneMock() {
  const { state, update } = useStore();
  const t = useTheme();
  const land = state.floatPos === 'right';
  const k = land ? 0.34 : 0.44; // mock pixels per logical dp
  const W = (land ? 860 : 400) * k;
  const H = (land ? 400 : 860) * k;
  const sz = state.floatSize / 100;
  const long = 122 * sz, short = 34 * sz;
  const pw = (land ? short : long) * k;
  const ph = (land ? long : short) * k;

  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null);
  const live = useRef({ pos: state.floatPos, x: state.floatX, y: state.floatY, W, H });
  live.current = { pos: state.floatPos, x: state.floatX, y: state.floatY, W, H };
  const from = useRef({ x: 50, y: 2 });
  const last = useRef({ x: 50, y: 2 });

  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => live.current.pos !== 'right',
    onMoveShouldSetPanResponder: () => live.current.pos !== 'right',
    onPanResponderTerminationRequest: () => false,
    onPanResponderGrant: () => {
      const s = live.current;
      from.current = s.pos === 'custom' ? { x: s.x, y: s.y } : { x: 50, y: 2 };
      last.current = from.current;
      setDrag(from.current);
    },
    onPanResponderMove: (_e, g) => {
      const s = live.current;
      const n = { x: clamp(Math.round(from.current.x + (g.dx / s.W) * 100), 0, 100), y: clamp(Math.round((from.current.y + (g.dy / s.H) * 100) * 2) / 2, 0, 92) };
      last.current = n;
      setDrag(n);
    },
    onPanResponderRelease: () => { const n = last.current; setDrag(null); api.storageSet('islandSpot', ''); update((x) => ({ ...x, floatPos: 'custom', floatX: n.x, floatY: n.y })); },
    onPanResponderTerminate: () => setDrag(null),
  })).current;

  const x = drag ? drag.x : state.floatX;
  const y = drag ? drag.y : state.floatY;
  const dx = state.floatDx * k, dy = state.floatDy * k;
  let left: number, top: number;
  if (state.floatPos === 'top') { left = (W - pw) / 2 + dx; top = 8 * k + dy; }
  else if (state.floatPos === 'right') { left = W - pw + dx; top = (H - ph) / 2 + dy; }
  else { left = (W * x) / 100 - pw / 2 + dx; top = (H * y) / 100 + dy; }
  left = clamp(left, 0, W - pw); top = clamp(top, 0, H - ph);

  const col = /^#[0-9a-fA-F]{6}$/.test(state.floatColor) ? state.floatColor : '#000000';
  const label = isLight(col) ? '#1b6fb5' : '#8cc4f5';
  const hole = 10 * k * 2;

  return (
    <View style={{ alignItems: 'center', paddingVertical: 6 }}>
      <View style={{ padding: 5, borderRadius: 30, backgroundColor: '#0c0e11' }}>
        <View style={{ width: W, height: H, borderRadius: 26, overflow: 'hidden', backgroundColor: '#cfd7e2' }}>
          <View style={{ position: 'absolute', left: W * 0.12, right: W * 0.12, top: H * 0.3, height: H * 0.05, borderRadius: 8, backgroundColor: 'rgba(255,255,255,.55)' }} />
          <View style={{ position: 'absolute', left: W * 0.12, right: W * 0.35, top: H * 0.4, height: H * 0.05, borderRadius: 8, backgroundColor: 'rgba(255,255,255,.4)' }} />
          {/* the camera hole, so the island can be lined up over it */}
          <View style={land ? { position: 'absolute', right: 3 * k, top: H / 2 - hole / 2, width: hole, height: hole, borderRadius: hole / 2, backgroundColor: '#3a3f47' }
            : { position: 'absolute', left: W / 2 - hole / 2, top: 5 * k, width: hole, height: hole, borderRadius: hole / 2, backgroundColor: '#3a3f47' }} />
          <View {...pan.panHandlers} style={{ position: 'absolute', left, top, width: pw, height: ph, borderRadius: Math.min(pw, ph) / 2, backgroundColor: col, opacity: state.floatOpacity / 100, alignItems: 'center', justifyContent: 'center', borderWidth: 0.5, borderColor: 'rgba(128,128,128,.35)' }}>
            {state.floatName && !land ? <Text style={{ color: label, fontSize: Math.max(5, 11.5 * sz * k * 1.25), fontWeight: '700', letterSpacing: 1.2 }}>DENSIFY</Text> : null}
          </View>
        </View>
      </View>
      <T v="sub" style={{ marginTop: 10, textAlign: 'center' }}>
        {state.floatPos === 'right' ? 'Right: the island sits on the camera side when you hold the phone sideways.' : 'Drag the island anywhere to place it, or pick a preset above.'}
      </T>
    </View>
  );
}

function Label({ children, right }: { children: React.ReactNode; right?: string }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', marginTop: 18, marginBottom: 2 }}>
      <T w="600">{children}</T>
      {right ? <T v="sub">{right}</T> : null}
    </View>
  );
}

/** Everything about the dynamic island in one block: position, size, colour, opacity and the optional name. */
export function IslandSettings({ onPreview }: { onPreview: () => void }) {
  const { state, update } = useStore();
  const t = useTheme();
  const [hex, setHex] = useState(state.floatColor);
  const set = (p: Partial<typeof state>) => update((s) => ({ ...s, ...p }));
  const applyHex = (v: string) => {
    if (!HEX.test(v)) { setHex(state.floatColor); return; }
    const c = ('#' + v.replace('#', '')).toLowerCase();
    setHex(c); set({ floatColor: c });
  };
  const nudge = (ax: 'floatDx' | 'floatDy', d: number) => { tap(); update((s) => ({ ...s, [ax]: clamp(s[ax] + d, -120, 120) })); };
  const arrow = (name: 'chevron-left' | 'chevron-right' | 'expand-less' | 'expand-more', ax: 'floatDx' | 'floatDy', d: number) => (
    <Pressable onPress={() => nudge(ax, d)} onLongPress={() => nudge(ax, d * 5)} style={{ width: 46, height: 42, borderRadius: t.rs, backgroundColor: t.c.acs, alignItems: 'center', justifyContent: 'center' }}>
      <Icon name={name} size={24} color={t.c.ac} />
    </Pressable>
  );

  return (
    <View style={{ paddingHorizontal: 16, paddingVertical: 14 }}>
      <T w="600">Mode</T>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
        <Chip h={36} label="Fixed" on={!state.floatMove} onPress={() => set({ floatMove: false })} />
        <Chip h={36} label="Movable" on={state.floatMove} onPress={() => set({ floatMove: true })} />
      </View>
      <T v="sub" style={{ marginTop: 8 }}>
        {state.floatMove ? 'Drag the closed island in your game to move it. It remembers where you leave it.' : 'The island stays exactly where the position below puts it.'}
      </T>
      {state.floatMove ? (
        <View style={{ marginTop: 10, flexDirection: 'row' }}>
          <Btn label="Reset moved position" v="tonal" onPress={() => api.storageSet('islandSpot', '')} />
        </View>
      ) : null}

      <View style={{ marginTop: 18 }}><T w="600">Position</T></View>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
        {([['top', 'Top (camera)'], ['right', 'Right (landscape)'], ['custom', 'Custom']] as const).map(([k, l]) => (
          <Chip key={k} h={36} label={l} on={state.floatPos === k} onPress={() => { api.storageSet('islandSpot', ''); set({ floatPos: k }); }} />
        ))}
      </View>
      <View style={{ marginTop: 12 }}><PhoneMock /></View>

      <Label right={`${state.floatSize}%`}>Size</Label>
      <Slider value={state.floatSize} min={70} max={150} onChange={(v) => set({ floatSize: Math.round(v / 5) * 5 })} />

      <Label>Colour</Label>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 8, alignItems: 'center' }}>
        {SWATCHES.map((c) => {
          const on = state.floatColor.toLowerCase() === c;
          return (
            <Pressable key={c} onPress={() => { tap(); setHex(c); set({ floatColor: c }); }}
              style={{ width: 34, height: 34, borderRadius: 17, backgroundColor: c, borderWidth: on ? 3 : 1, borderColor: on ? t.c.ac : t.c.bd2 }} />
          );
        })}
      </View>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, marginTop: 12 }}>
        <View style={{ width: 34, height: 34, borderRadius: 8, backgroundColor: HEX.test(hex) ? ('#' + hex.replace('#', '')) : state.floatColor, borderWidth: 1, borderColor: t.c.bd2 }} />
        <TextInput value={hex} onChangeText={setHex} onEndEditing={() => applyHex(hex)} onSubmitEditing={() => applyHex(hex)}
          autoCapitalize="none" autoCorrect={false} maxLength={7} placeholder="#000000" placeholderTextColor={t.c.mu}
          style={{ flex: 1, height: 42, borderRadius: t.rs, borderWidth: 1, borderColor: t.c.bd2, paddingHorizontal: 12, color: t.c.tx, fontSize: 15 }} />
        <Btn label="Black" v="tonal" onPress={() => { setHex('#000000'); set({ floatColor: '#000000' }); }} />
      </View>

      <Label right={`${state.floatOpacity}%`}>Opacity</Label>
      <Slider value={state.floatOpacity} min={30} max={100} onChange={(v) => set({ floatOpacity: Math.round(v / 5) * 5 })} />

      <Label right={`x ${state.floatDx} · y ${state.floatDy} dp`}>Line up over the camera</Label>
      <T v="sub" style={{ marginBottom: 10 }}>Nudge the island until it covers the camera hole exactly. Hold an arrow to move 5 dp at a time.</T>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        {arrow('chevron-left', 'floatDx', -1)}
        {arrow('chevron-right', 'floatDx', 1)}
        {arrow('expand-less', 'floatDy', -1)}
        {arrow('expand-more', 'floatDy', 1)}
        <View style={{ flex: 1 }} />
        <Btn label="Reset" v="text" onPress={() => set({ floatDx: 0, floatDy: 0 })} />
      </View>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14, marginTop: 18 }}>
        <View style={{ flex: 1 }}>
          <T w="600">Write DENSIFY</T>
          <T v="sub">Show the name in light blue inside the island. Off keeps it pure black.</T>
        </View>
        <AppSwitch on={state.floatName} onChange={(v) => set({ floatName: v })} />
      </View>

      <View style={{ marginTop: 18, flexDirection: 'row' }}>
        <Btn label="Preview on screen" v="pri" icon="visibility" onPress={onPreview} />
      </View>
    </View>
  );
}
