import React, { createContext, RefObject, useContext, useEffect, useRef } from 'react';
import { Animated, Easing, Platform, StyleSheet, View } from 'react-native';
import { BlurTargetView, BlurView } from 'expo-blur';
import { LinearGradient } from 'expo-linear-gradient';
import { useTheme } from '../theme/ThemeContext';

const GlassCtx = createContext<RefObject<View | null> | null>(null);

/** Holds the one view Android blurs behind the glass surfaces. */
export function GlassProvider({ children }: { children: React.ReactNode }) {
  const ref = useRef<View>(null);
  return <GlassCtx.Provider value={ref}>{children}</GlassCtx.Provider>;
}

const rgba = (hex: string, a: number) => {
  const n = parseInt(hex.replace('#', '').slice(0, 6), 16) || 0;
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

/** The frosted layer inside a glass surface. */
export function GlassBlur({ intensity = 40 }: { intensity?: number }) {
  const t = useTheme(); const ref = useContext(GlassCtx);
  if (Platform.OS === 'android' && !ref) return null;
  return <BlurView pointerEvents="none" intensity={intensity} tint={t.dark ? 'dark' : 'light'} blurTarget={ref ?? undefined} blurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />;
}

/** A long, soft-edged band of colour. Both ends fade out, so there are no hard edges, only a smooth wash. */
function Band({ color, alpha, top, rot, style }: { color: string; alpha: number; top: string; rot: string; style?: object }) {
  return (
    <Animated.View pointerEvents="none" style={[{ position: 'absolute', left: '-35%', width: '170%', top, height: '55%', transform: [{ rotate: rot }] }, style]}>
      <LinearGradient colors={[rgba(color, 0), rgba(color, alpha), rgba(color, 0)]} locations={[0, 0.5, 1]} start={{ x: 0.5, y: 0 }} end={{ x: 0.5, y: 1 }} style={StyleSheet.absoluteFill} />
    </Animated.View>
  );
}

/** Frosted Glass: still, pastel light that the milky panes blur. */
function FrostedArt() {
  const t = useTheme();
  return (
    <>
      <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />
      <Band color="#9ec5ff" alpha={0.7} top="-6%" rot="-18deg" />
      <Band color="#d6bfff" alpha={0.6} top="30%" rot="-18deg" />
      <Band color="#ffcfae" alpha={0.5} top="62%" rot="-18deg" />
    </>
  );
}

/** Fluid Glass: aurora colour that drifts slowly behind clear glass. Cheap: only transforms move, on the native thread. */
function FluidArt() {
  const t = useTheme();
  const a = useRef(new Animated.Value(0)).current;
  const b = useRef(new Animated.Value(0)).current;
  const c = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const run = (v: Animated.Value, ms: number) => Animated.loop(Animated.sequence([
      Animated.timing(v, { toValue: 1, duration: ms, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      Animated.timing(v, { toValue: 0, duration: ms, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
    ]));
    const loops = [run(a, 9000), run(b, 13000), run(c, 17000)];
    loops.forEach((l) => l.start());
    return () => loops.forEach((l) => l.stop());
  }, [a, b, c]);
  const drift = (v: Animated.Value, x: number, y: number) => ({ transform: [{ translateX: v.interpolate({ inputRange: [0, 1], outputRange: [-x, x] }) }, { translateY: v.interpolate({ inputRange: [0, 1], outputRange: [-y, y] }) }, { rotate: '-24deg' }] });
  return (
    <>
      <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />
      <Band color={t.c.ac} alpha={0.55} top="-8%" rot="0deg" style={drift(a, 50, 70)} />
      <Band color="#8b5cf6" alpha={0.5} top="28%" rot="0deg" style={drift(b, 70, 50)} />
      <Band color="#f472b6" alpha={0.38} top="58%" rot="0deg" style={drift(c, 40, 80)} />
    </>
  );
}

/** Theme background behind every screen. With liquid glass it becomes a soft glowing scene that the glass blurs. */
export function Backdrop({ target }: { target?: boolean }) {
  const t = useTheme(); const ref = useContext(GlassCtx);
  if (!t.glass) return <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />;
  if (t.material) {
    const scene = <View pointerEvents="none" style={[StyleSheet.absoluteFill, { overflow: 'hidden' }]}>{t.material === 'fluid' ? <FluidArt /> : <FrostedArt />}</View>;
    if (target && ref) return <BlurTargetView ref={ref} pointerEvents="none" style={StyleSheet.absoluteFill}>{scene}</BlurTargetView>;
    return scene;
  }
  const ink = t.id === 'manga'; // Manga stays black and white: neutral grey light instead of a coloured glow
  const tint = ink ? '#8a8a86' : t.c.ac;
  const second = t.dark ? '#7c5cff' : t.id === 'paper' ? '#8cc8ff' : ink ? '#c4c4c0' : '#ff9ac8';
  const a1 = t.dark ? 0.15 : 0.14; const a2 = t.dark ? 0.11 : 0.12;
  // Two long, smooth diagonal washes (no discs, no hard rings): soft colour from the top-left and a faint second tone at the bottom-right.
  const art = (
    <>
      <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />
      <LinearGradient pointerEvents="none" colors={[rgba(tint, a1), rgba(tint, a1 * 0.7), rgba(tint, a1 * 0.4), rgba(tint, a1 * 0.15), rgba(tint, 0)]} locations={[0, 0.2, 0.45, 0.7, 1]} start={{ x: 0, y: 0 }} end={{ x: 0.85, y: 0.75 }} style={StyleSheet.absoluteFill} />
      <LinearGradient pointerEvents="none" colors={[rgba(second, 0), rgba(second, a2 * 0.25), rgba(second, a2 * 0.6), rgba(second, a2)]} locations={[0.35, 0.6, 0.82, 1]} start={{ x: 0.1, y: 0.15 }} end={{ x: 1, y: 1 }} style={StyleSheet.absoluteFill} />
    </>
  );
  if (target && ref) return <BlurTargetView ref={ref} pointerEvents="none" style={StyleSheet.absoluteFill}>{art}</BlurTargetView>;
  return <View pointerEvents="none" style={StyleSheet.absoluteFill}>{art}</View>;
}
