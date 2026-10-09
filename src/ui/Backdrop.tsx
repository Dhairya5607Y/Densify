import React from 'react';
import { StyleSheet, View } from 'react-native';
import { BlurView } from 'expo-blur';
import { useTheme } from '../theme/ThemeContext';
import { hexA } from './TabBar';

/** Soft colour field behind glass surfaces so the blur has something to refract. */
export function Backdrop() {
  const t = useTheme();
  if (!t.backdrop) return <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} />;
  const [a, b, c] = t.backdrop;
  return (
    <View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg }]} pointerEvents="none">
      <View style={{ position: 'absolute', top: -120, left: -100, width: 380, height: 380, borderRadius: 190, backgroundColor: hexA(a, 0.55) }} />
      <View style={{ position: 'absolute', top: 260, right: -140, width: 360, height: 360, borderRadius: 180, backgroundColor: hexA(c, 0.4) }} />
      <View style={{ position: 'absolute', bottom: -140, left: -80, width: 400, height: 400, borderRadius: 200, backgroundColor: hexA(b, 0.45) }} />
      <BlurView intensity={90} tint="dark" experimentalBlurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />
    </View>
  );
}
