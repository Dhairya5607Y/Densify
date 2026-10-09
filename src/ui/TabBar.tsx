import React, { useEffect, useRef } from 'react';
import { Animated, Pressable, StyleSheet, View } from 'react-native';
import { BlurView } from 'expo-blur';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../theme/ThemeContext';
import { Icon, IconName, T, tap } from './primitives';
import { useScrollDir } from './scroll';

export const hexA = (hex: string, a: number) => {
  const h = hex.replace('#', ''); const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

export type Tab = 'home' | 'games' | 'settings';
const TABS: { k: Tab; label: string; icon: IconName }[] = [{ k: 'home', label: 'Home', icon: 'home' }, { k: 'games', label: 'Games', icon: 'sports-esports' }, { k: 'settings', label: 'Settings', icon: 'tune' }];

export function TabBar({ tab, onTab }: { tab: Tab; onTab: (t: Tab) => void }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { dir } = useScrollDir();
  const solid = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.timing(solid, { toValue: dir === 'down' ? 1 : 0, duration: 220, useNativeDriver: true }).start(); }, [dir]);
  return (
    <View style={{ position: 'absolute', left: 0, right: 0, bottom: 0, paddingBottom: insets.bottom, overflow: 'hidden' }}>
      <BlurView intensity={t.surface === 'glass' ? t.blur : 35} tint={t.dark ? 'dark' : 'light'} experimentalBlurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />
      <View style={[StyleSheet.absoluteFill, { backgroundColor: hexA(t.c.bg, t.surface === 'glass' ? 0.25 : 0.5) }]} />
      <Animated.View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg, opacity: solid }]} />
      <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: t.c.bd2 }} />
      <View style={{ flexDirection: 'row', paddingTop: 8, paddingBottom: 8 }}>
        {TABS.map((x) => {
          const on = x.k === tab;
          return (
            <Pressable key={x.k} onPress={() => { tap(); onTab(x.k); }} style={{ flex: 1, alignItems: 'center', gap: 4, minHeight: 56, justifyContent: 'center' }}>
              <View style={{ width: 60, height: 32, borderRadius: t.surface === 'hud' ? 2 : 16, alignItems: 'center', justifyContent: 'center', backgroundColor: on ? t.c.acs : 'transparent' }}>
                <Icon name={x.icon} color={on ? t.c.ac : t.c.mu} />
              </View>
              <T v="sub" c={on ? t.c.tx : t.c.mu} w="500" style={{ fontSize: 12 }}>{t.mono ? x.label.toUpperCase() : x.label}</T>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
