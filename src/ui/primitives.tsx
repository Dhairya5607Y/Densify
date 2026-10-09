import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, Image, PanResponder, Pressable, StyleProp, StyleSheet, Text, TextStyle, View, ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { MaterialIcons } from '@expo/vector-icons';
import * as Haptics from 'expo-haptics';
import { useTheme } from '../theme/ThemeContext';
import { api } from '../native/api';

export type IconName = React.ComponentProps<typeof MaterialIcons>['name'];
export const Icon = ({ name, size = 22, color }: { name: IconName; size?: number; color?: string }) => {
  const t = useTheme();
  return <MaterialIcons name={name} size={size} color={color ?? t.c.tx2} />;
};
export const tap = () => Haptics.selectionAsync().catch(() => {});

/* ---------- text ---------- */
type TV = 'title' | 'h' | 'body' | 'sub' | 'num';
export function T({ v = 'body', c, children, style, lines, w }: { v?: TV; c?: string; children: React.ReactNode; style?: StyleProp<TextStyle>; lines?: number; w?: TextStyle['fontWeight'] }) {
  const t = useTheme();
  const m: Record<TV, TextStyle> = {
    title: { fontSize: 30, fontWeight: '500', letterSpacing: -0.6, color: t.c.tx },
    h: { fontSize: 20, fontWeight: '500', color: t.c.tx },
    body: { fontSize: 16, fontWeight: '500', color: t.c.tx },
    sub: { fontSize: 13, color: t.c.mu, lineHeight: 18 },
    num: { fontSize: 64, fontWeight: '500', letterSpacing: -2.5, color: t.c.tx },
  };
  return <Text numberOfLines={lines} style={[m[v], t.mono && { fontFamily: 'monospace' }, c ? { color: c } : null, w ? { fontWeight: w } : null, style]}>{children}</Text>;
}

export function Section({ title, action, onAction }: { title: string; action?: string; onAction?: () => void }) {
  const t = useTheme();
  const txt = t.label === 'sentence' ? title : t.label === 'mono' ? `// ${title.toUpperCase()}` : title.toUpperCase();
  const st: TextStyle = t.label === 'sentence' ? { color: t.c.ac, fontSize: 13.5, fontWeight: '600' } : { color: t.label === 'mono' ? t.c.ac : t.c.mu, fontSize: 11.5, fontWeight: '600', letterSpacing: 1.3 };
  return (
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 24, marginBottom: 8, marginHorizontal: 10 }}>
      <Text style={[st, t.mono && { fontFamily: 'monospace' }]}>{txt}</Text>
      {action ? <Pressable onPress={onAction} hitSlop={10}><Text style={{ color: t.c.mu, fontSize: 13, fontWeight: '500' }}>{action}</Text></Pressable> : null}
    </View>
  );
}

/* ---------- surfaces ---------- */
const Corner = ({ s }: { s: ViewStyle }) => {
  const t = useTheme();
  return <View pointerEvents="none" style={[{ position: 'absolute', width: 9, height: 9, borderColor: t.c.ac }, s]} />;
};

export function Surface({ children, style, radius, pad }: { children?: React.ReactNode; style?: StyleProp<ViewStyle>; radius?: number; pad?: number }) {
  const t = useTheme();
  const base: ViewStyle = {
    borderRadius: radius ?? t.r, borderWidth: t.bw, borderColor: t.c.bd, overflow: 'hidden',
    backgroundColor: t.surface === 'outline' ? 'transparent' : t.c.sf, elevation: t.elev,
    shadowColor: '#0c1620', shadowOpacity: t.elev ? 0.08 : 0, shadowRadius: 8, shadowOffset: { width: 0, height: 2 }, padding: pad,
  };
  return (
    <View style={[base, style]}>
      {t.surface === 'glass' && <BlurView intensity={t.blur} tint="dark" experimentalBlurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />}
      {t.surface === 'glass' && <View pointerEvents="none" style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 1, backgroundColor: 'rgba(255,255,255,.28)' }} />}
      {t.surface === 'hud' && (<>
        <Corner s={{ top: 0, left: 0, borderTopWidth: 2, borderLeftWidth: 2 }} /><Corner s={{ top: 0, right: 0, borderTopWidth: 2, borderRightWidth: 2 }} />
        <Corner s={{ bottom: 0, left: 0, borderBottomWidth: 2, borderLeftWidth: 2 }} /><Corner s={{ bottom: 0, right: 0, borderBottomWidth: 2, borderRightWidth: 2 }} />
      </>)}
      {children}
    </View>
  );
}

export function Group({ children, style }: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  const kids = React.Children.toArray(children).filter(Boolean);
  return <Surface style={style}>{kids.map((k, i) => (React.isValidElement(k) && k.type === Row ? React.cloneElement(k as React.ReactElement<{ divider?: boolean }>, { divider: i > 0 }) : k))}</Surface>;
}

export function Row({ lead, leadIcon, title, sub, right, onPress, divider, disabled, minH = 64 }: { lead?: React.ReactNode; leadIcon?: IconName; title: React.ReactNode; sub?: string; right?: React.ReactNode; onPress?: () => void; divider?: boolean; disabled?: boolean; minH?: number }) {
  const t = useTheme();
  const leading = lead ?? (leadIcon ? (
    <View style={{ width: 40, height: 40, borderRadius: t.surface === 'hud' ? 2 : 20, backgroundColor: t.c.acs, alignItems: 'center', justifyContent: 'center' }}><Icon name={leadIcon} size={21} color={t.c.ac} /></View>) : null);
  return (
    <Pressable disabled={!onPress || disabled} onPress={() => { tap(); onPress?.(); }} android_ripple={onPress ? { color: t.c.acs } : undefined}
      style={{ flexDirection: 'row', alignItems: 'center', gap: 14, minHeight: minH, paddingHorizontal: 16, paddingVertical: 11, opacity: disabled ? 0.5 : 1, borderTopWidth: divider ? StyleSheet.hairlineWidth : 0, borderTopColor: t.c.bd2 }}>
      {leading}
      <View style={{ flex: 1 }}>
        {typeof title === 'string' ? <T lines={1}>{title}</T> : title}
        {sub ? <T v="sub" style={{ marginTop: 1 }}>{sub}</T> : null}
      </View>
      {right}
    </Pressable>
  );
}

/* ---------- controls ---------- */
export function Btn({ label, onPress, v = 'tonal', wide, disabled, icon }: { label: string; onPress: () => void; v?: 'pri' | 'tonal' | 'text'; wide?: boolean; disabled?: boolean; icon?: IconName }) {
  const t = useTheme();
  const bg = v === 'pri' ? t.c.ac : v === 'tonal' ? t.c.acs : 'transparent';
  const fg = v === 'pri' ? t.c.on : t.c.ac;
  return (
    <Pressable disabled={disabled} onPress={() => { tap(); onPress(); }}
      style={({ pressed }) => ({ height: wide ? 52 : 44, paddingHorizontal: 22, borderRadius: t.rb, backgroundColor: disabled ? (v === 'text' ? 'transparent' : t.c.sf2) : bg, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', gap: 8, alignSelf: wide ? 'stretch' : 'auto', transform: [{ scale: pressed ? 0.97 : 1 }], borderWidth: t.surface === 'hud' && v !== 'text' ? 1 : 0, borderColor: t.c.ac })}>
      {icon ? <Icon name={icon} size={19} color={disabled ? t.c.mu : fg} /> : null}
      <Text style={{ color: disabled ? t.c.mu : fg, fontWeight: '600', fontSize: wide ? 16 : 15, fontFamily: t.mono ? 'monospace' : undefined, letterSpacing: t.mono ? 0.8 : 0, textTransform: t.mono ? 'uppercase' : 'none' }}>{label}</Text>
    </Pressable>
  );
}

export function Chip({ label, on, onPress, h = 44 }: { label: string; on: boolean; onPress: () => void; h?: number }) {
  const t = useTheme();
  return (
    <Pressable onPress={() => { tap(); onPress(); }} style={{ height: h, paddingHorizontal: 16, borderRadius: Math.min(t.rs + 2, h / 2), borderWidth: 1.2, borderColor: on ? t.c.ac : t.c.bd2, backgroundColor: on ? t.c.acs : 'transparent', alignItems: 'center', justifyContent: 'center' }}>
      <T c={on ? t.c.ac : t.c.tx2} style={{ fontSize: 15 }}>{label}</T>
    </Pressable>
  );
}

export function AppSwitch({ on, onChange, disabled }: { on: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  const t = useTheme();
  const a = useRef(new Animated.Value(on ? 1 : 0)).current;
  useEffect(() => { Animated.timing(a, { toValue: on ? 1 : 0, duration: 200, easing: Easing.out(Easing.cubic), useNativeDriver: false }).start(); }, [on]);
  const s = t.switchStyle;
  const W = s === 'm3' ? 52 : s === 'ios' ? 51 : 46, H = s === 'm3' ? 32 : s === 'ios' ? 31 : 24;
  const th = s === 'm3' ? a.interpolate({ inputRange: [0, 1], outputRange: [16, 24] }) : s === 'ios' ? 27 : 14;
  const left = s === 'm3' ? a.interpolate({ inputRange: [0, 1], outputRange: [6, W - 4 - 24 - 2] }) : s === 'ios' ? a.interpolate({ inputRange: [0, 1], outputRange: [2, W - 29] }) : a.interpolate({ inputRange: [0, 1], outputRange: [3, W - 17] });
  const bg = a.interpolate({ inputRange: [0, 1], outputRange: [s === 'm3' ? 'rgba(0,0,0,0)' : t.dark ? 'rgba(120,120,128,.4)' : 'rgba(120,120,128,.3)', t.c.ac] });
  const thumbColor = a.interpolate({ inputRange: [0, 1], outputRange: [s === 'm3' ? t.c.mu : '#ffffff', s === 'ios' ? '#ffffff' : t.c.on] });
  return (
    <Pressable disabled={disabled} onPress={() => { tap(); onChange(!on); }} hitSlop={8} accessibilityRole="switch" accessibilityState={{ checked: on }}>
      <Animated.View style={{ width: W, height: H, borderRadius: s === 'hud' ? 2 : H / 2, backgroundColor: bg, borderWidth: s === 'm3' ? 2 : s === 'hud' ? 1 : 0, borderColor: a.interpolate({ inputRange: [0, 1], outputRange: [s === 'hud' ? t.c.bd2 : t.c.mu, t.c.ac] }), justifyContent: 'center' }}>
        <Animated.View style={{ position: 'absolute', left, width: th, height: th, borderRadius: s === 'hud' ? 1 : 20, backgroundColor: thumbColor, shadowColor: '#000', shadowOpacity: s === 'ios' ? 0.25 : 0, shadowRadius: 3, shadowOffset: { width: 0, height: 1.5 }, elevation: s === 'ios' ? 2 : 0 }} />
      </Animated.View>
    </Pressable>
  );
}

export function Slider({ value, min, max, onChange }: { value: number; min: number; max: number; onChange: (v: number) => void }) {
  const t = useTheme();
  const [w, setW] = useState(1);
  const ref = useRef<View>(null);
  const st = useRef({ w: 1, left: 0, min, max, cb: onChange });
  st.current = { ...st.current, w, min, max, cb: onChange };
  const set = (x: number) => { const r = Math.max(0, Math.min(1, x / st.current.w)); st.current.cb(Math.round(st.current.min + r * (st.current.max - st.current.min))); };
  const pan = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true, onMoveShouldSetPanResponder: () => true, onPanResponderTerminationRequest: () => false,
    onPanResponderGrant: (_e, g) => { ref.current?.measureInWindow((x) => { st.current.left = x; set(g.x0 - x); }); },
    onPanResponderMove: (_e, g) => set(g.moveX - st.current.left),
  })).current;
  const pct = Math.max(0, Math.min(1, (value - min) / (max - min)));
  const x = pct * w;
  const s = t.sliderStyle;
  const trackH = s === 'bar' ? 14 : s === 'ios' ? 6 : 3;
  return (
    <View ref={ref} onLayout={(e) => setW(e.nativeEvent.layout.width)} {...pan.panHandlers} style={{ height: 48, justifyContent: 'center' }}>
      {s === 'bar' ? (<>
        <View style={{ position: 'absolute', left: 0, width: Math.max(0, x - 6), height: trackH, borderTopLeftRadius: 7, borderBottomLeftRadius: 7, borderTopRightRadius: 2, borderBottomRightRadius: 2, backgroundColor: t.c.ac }} />
        <View style={{ position: 'absolute', left: Math.min(w, x + 6), right: 0, height: trackH, borderTopRightRadius: 7, borderBottomRightRadius: 7, borderTopLeftRadius: 2, borderBottomLeftRadius: 2, backgroundColor: t.c.track }} />
        <View style={{ position: 'absolute', left: x - 2.5, width: 5, height: 40, borderRadius: 3, backgroundColor: t.c.ac }} />
      </>) : (<>
        <View style={{ height: trackH, borderRadius: trackH / 2, backgroundColor: t.c.track }} />
        <View style={{ position: 'absolute', left: 0, width: x, height: trackH, borderRadius: trackH / 2, backgroundColor: t.c.ac }} />
        {s === 'hud' && <View style={{ position: 'absolute', left: 0, right: 0, flexDirection: 'row', justifyContent: 'space-between', top: 28 }}>{Array.from({ length: 11 }).map((_, i) => <View key={i} style={{ width: 1, height: i % 5 === 0 ? 9 : 5, backgroundColor: t.c.bd2 }} />)}</View>}
        <View style={s === 'ios' ? { position: 'absolute', left: x - 14, width: 28, height: 28, borderRadius: 14, backgroundColor: '#fff', shadowColor: '#000', shadowOpacity: 0.3, shadowRadius: 4, shadowOffset: { width: 0, height: 2 }, elevation: 3 }
          : { position: 'absolute', left: x - 7, width: 14, height: 14, backgroundColor: t.c.ac, transform: [{ rotate: '45deg' }], borderWidth: 2, borderColor: t.c.bg }} />
      </>)}
    </View>
  );
}

export function AnimatedNumber({ value, style }: { value: number; style?: StyleProp<TextStyle> }) {
  const a = useRef(new Animated.Value(value)).current;
  const [n, setN] = useState(value);
  useEffect(() => {
    const id = a.addListener(({ value: v }) => setN(Math.round(v)));
    Animated.timing(a, { toValue: value, duration: 260, easing: Easing.out(Easing.cubic), useNativeDriver: false }).start();
    return () => a.removeListener(id);
  }, [value]);
  return <Text style={style}>{n}</Text>;
}

export function Ruler({ value, def }: { value: number; def: number }) {
  const t = useTheme();
  const pct = (v: number) => Math.max(0, Math.min(100, ((v - 240) / 560) * 100));
  const a = useRef(new Animated.Value(pct(value))).current;
  useEffect(() => { Animated.timing(a, { toValue: pct(value), duration: 380, easing: Easing.out(Easing.cubic), useNativeDriver: false }).start(); }, [value]);
  const pos = (v: Animated.Value | number) => (typeof v === 'number' ? `${v}%` : v.interpolate({ inputRange: [0, 100], outputRange: ['0%', '100%'] }));
  return (
    <View>
      <View style={{ height: 30, marginTop: 10 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end', height: 30 }}>
          {Array.from({ length: 57 }).map((_, i) => <View key={i} style={{ width: 1, height: i % 7 === 0 ? 15 : 7, backgroundColor: i % 7 === 0 ? t.c.mu : t.c.bd2 }} />)}
        </View>
        {def > 0 && <View style={{ position: 'absolute', bottom: 0, left: pos(pct(def)) as unknown as ViewStyle['left'], height: 17, borderLeftWidth: 1, borderStyle: 'dashed', borderColor: t.c.mu }} />}
        <Animated.View style={{ position: 'absolute', bottom: 0, left: pos(a) as unknown as ViewStyle['left'], width: 3, marginLeft: -1.5, height: 30, borderRadius: 2, backgroundColor: t.c.ac }} />
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 }}><T v="sub">240</T><T v="sub">800</T></View>
    </View>
  );
}

/* ---------- app icons (loaded from the device, never bundled) ---------- */
const iconCache = new Map<string, string | null>();
const hue = (s: string) => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360; return h; };
export function AppIcon({ pkg, name, size = 46 }: { pkg: string; name: string; size?: number }) {
  const t = useTheme();
  const [b64, setB64] = useState<string | null | undefined>(iconCache.get(pkg));
  useEffect(() => {
    let live = true;
    if (!iconCache.has(pkg)) api.getAppIcon(pkg).then((r) => { iconCache.set(pkg, r); if (live) setB64(r); });
    return () => { live = false; };
  }, [pkg]);
  const r = t.surface === 'hud' ? 6 : size * 0.3;
  if (b64) return <Image source={{ uri: `data:image/png;base64,${b64}` }} style={{ width: size, height: size, borderRadius: r }} />;
  return <View style={{ width: size, height: size, borderRadius: r, backgroundColor: `hsl(${hue(pkg)},45%,38%)`, alignItems: 'center', justifyContent: 'center' }}><Text style={{ color: '#fff', fontSize: size * 0.42, fontWeight: '600' }}>{name.charAt(0).toUpperCase()}</Text></View>;
}
