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

/** A soft round glow made of stacked translucent discs, since React Native has no radial gradient. */
function Glow({ size, color, style }: { size: number; color: string; style: object }) {
  return (
    <View pointerEvents="none" style={[{ position: 'absolute', width: size, height: size }, style]}>
      {Array.from({ length: 9 }).map((_, i) => {
        const s = size * (1 - i * 0.1);
        return <View key={i} style={{ position: 'absolute', left: (size - s) / 2, top: (size - s) / 2, width: s, height: s, borderRadius: s / 2, backgroundColor: color }} />;
      })}
    </View>
  );
}

/** Theme background behind every screen. With liquid glass it becomes a soft glowing scene that the glass blurs. */
export function Backdrop({ target }: { target?: boolean }) {
  const t = useTheme(); const ref = useContext(GlassCtx);
  if (!t.glass) return <View pointerEvents="none" style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />;
  const ink = t.id === 'manga'; // Manga stays black and white: neutral grey light instead of a coloured glow
  const tint = ink ? '#8a8a86' : t.c.ac;
  const second = t.dark ? '#7c5cff' : t.id === 'paper' ? '#8cc8ff' : ink ? '#c4c4c0' : '#ff9ac8';
  const art = (
    <>
      <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />
      <LinearGradient pointerEvents="none" colors={[rgba(tint, t.dark ? 0.22 : 0.24), 'rgba(0,0,0,0)', rgba(second, t.dark ? 0.16 : 0.2)]} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={StyleSheet.absoluteFill} />
      <Glow size={360} color={rgba(tint, t.dark ? 0.045 : 0.06)} style={{ top: -120, left: -110 }} />
      <Glow size={320} color={rgba(second, t.dark ? 0.04 : 0.055)} style={{ bottom: 40, right: -120 }} />
      <Glow size={220} color={rgba(tint, t.dark ? 0.035 : 0.05)} style={{ top: '45%', left: -80 }} />
    </>
  );
  if (target && ref) return <BlurTargetView ref={ref} pointerEvents="none" style={StyleSheet.absoluteFill}>{art}</BlurTargetView>;
  return <View pointerEvents="none" style={StyleSheet.absoluteFill}>{art}</View>;
}
