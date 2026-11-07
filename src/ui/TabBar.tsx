import React, { useEffect, useRef, useState } from 'react';
import { Animated, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../theme/ThemeContext';
import { Icon, IconName, T, tap } from './primitives';
import { useScrollDir } from './scroll';

export const hexA = (hex: string, a: number) => {
  const h = hex.replace('#', ''); const n = parseInt(h.length === 3 ? h.split('').map((x) => x + x).join('') : h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

export type Tab = 'games' | 'stats' | 'plugins' | 'settings';
const TABS: { k: Tab; label: string; icon: IconName }[] = [{ k: 'games', label: 'Home', icon: 'home' }, { k: 'stats', label: 'Stats', icon: 'insights' }, { k: 'plugins', label: 'Plugins', icon: 'extension' }, { k: 'settings', label: 'Settings', icon: 'tune' }];

/** Floating pill bar with a sliding highlight. */
export function TabBar({ tab, onTab }: { tab: Tab; onTab: (t: Tab) => void }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { dir } = useScrollDir();
  const [w, setW] = useState(0);
  const idx = Math.max(0, TABS.findIndex((x) => x.k === tab));
  const slide = useRef(new Animated.Value(idx)).current;
  const solid = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.spring(slide, { toValue: idx, useNativeDriver: true, damping: 18, stiffness: 200, mass: 0.8 }).start(); }, [idx]);
  useEffect(() => { Animated.timing(solid, { toValue: dir === 'down' ? 1 : 0, duration: 220, useNativeDriver: true }).start(); }, [dir]);
  const hud = t.surface === 'hud';
  const radius = hud ? 8 : 28;
  const inner = Math.max(0, w - 12);
  const cell = inner / TABS.length;
  return (
    <View pointerEvents="box-none" style={{ position: 'absolute', left: 16, right: 16, bottom: Math.max(insets.bottom, 10) + 6 }}>
      <View onLayout={(e) => setW(e.nativeEvent.layout.width)} style={{ borderRadius: radius, borderWidth: 1, borderColor: t.c.bd2, backgroundColor: t.c.sf, padding: 6, flexDirection: 'row', shadowColor: '#000', shadowOpacity: 0.28, shadowRadius: 18, shadowOffset: { width: 0, height: 8 }, elevation: 12, overflow: 'hidden' }}>
        <Animated.View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.bg, opacity: solid.interpolate({ inputRange: [0, 1], outputRange: [0, 0.35] }) }]} />
        {cell > 0 && (
          <Animated.View style={{ position: 'absolute', top: 6, bottom: 6, left: 6, width: cell, borderRadius: radius - 6, backgroundColor: t.c.acs, borderWidth: 1, borderColor: hexA(t.c.ac, 0.35), transform: [{ translateX: slide.interpolate({ inputRange: [0, TABS.length - 1], outputRange: [0, cell * (TABS.length - 1)] }) }] }} />
        )}
        {TABS.map((x) => {
          const on = x.k === tab;
          return (
            <Pressable key={x.k} onPress={() => { tap(); onTab(x.k); }} style={{ flex: 1, height: 54, alignItems: 'center', justifyContent: 'center', gap: 3 }}>
              <Icon name={x.icon} size={22} color={on ? t.c.ac : t.c.mu} />
              <T v="sub" c={on ? t.c.tx : t.c.mu} w={on ? '600' : '500'} style={{ fontSize: 11, lineHeight: 13 }}>{t.mono ? x.label.toUpperCase() : x.label}</T>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}
