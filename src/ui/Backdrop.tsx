import React, { createContext, RefObject, useContext, useRef } from 'react';
import { Platform, StyleSheet, View } from 'react-native';
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

/** Theme background behind every screen. With liquid glass it becomes a soft glowing scene that the glass blurs. */
export function Backdrop({ target }: { target?: boolean }) {
  const t = useTheme(); const ref = useContext(GlassCtx);
  if (!t.glass) return <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />;
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
