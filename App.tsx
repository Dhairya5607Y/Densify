import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, PanResponder, StyleSheet, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { api, useAdb } from './src/native/api';
import { NavProvider, useNav } from './src/nav';
import { StoreProvider, useStore } from './src/store/store';
import { ThemeProvider } from './src/theme/ThemeContext';
import { themeById, withAccent, withGlass } from './src/theme/themes';
import { PermissionProvider } from './src/logic/permissions';
import { Backdrop, GlassProvider } from './src/ui/Backdrop';
import { ToastProvider } from './src/ui/overlays';
import { ScrollDirProvider } from './src/ui/scroll';
import { TabBar, TAB_ORDER } from './src/ui/TabBar';
import { Games } from './src/screens/Games';
import { Presets } from './src/screens/Presets';
import { Profile } from './src/screens/Profile';
import { Settings } from './src/screens/Settings';
import { Stats } from './src/screens/Stats';
import { Setup } from './src/screens/Setup';
import { Tools } from './src/screens/Tools';
import { Plugins } from './src/screens/Plugins';
import { PluginUI } from './src/screens/PluginUI';
import { Apps } from './src/screens/Apps';
import { Developer } from './src/screens/Developer';
import { SettingsEditor } from './src/screens/SettingsEditor';
import { Recorder } from './src/screens/Recorder';
import { CrashScreen, installCrashHandler } from './src/screens/Crash';

function Slide({ children }: { children: React.ReactNode }) {
  const a = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.timing(a, { toValue: 1, duration: 240, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start(); }, [a]);
  return <Animated.View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, opacity: a, transform: [{ translateX: a.interpolate({ inputRange: [0, 1], outputRange: [28, 0] }) }] }}>{children}</Animated.View>;
}

function Shell() {
  const { state, update } = useStore();
  const nav = useNav();
  const adb = useAdb();
  useEffect(() => { if (state.automation && !api.isMonitoring()) api.startMonitoring(); }, []);
  // Until the user picks a default, it is the phone's own smallest width. It is filled in once, the first time Wireless Debugging connects,
  // and never replaced afterwards, so a value the user typed (such as 420) always stays.
  useEffect(() => {
    if (adb.state !== 'connected' || state.defaultSw > 0) return;
    api.readSw().then((d) => { if (d.physSw > 0) update((s) => (s.defaultSw > 0 ? s : { ...s, defaultSw: d.physSw })); }).catch(() => {});
  }, [adb.state]);
  const r = nav.route;
  // Swipe sideways to move between the bottom tabs (only on a tab, not while a sub-screen is open)
  const live = useRef({ tab: nav.tab, open: !!r, go: nav.setTab });
  live.current = { tab: nav.tab, open: !!r, go: nav.setTab };
  const shift = useRef(new Animated.Value(0)).current;
  const fade = useRef(new Animated.Value(1)).current;
  const prevIdx = useRef(TAB_ORDER.indexOf(nav.tab));
  // Every tab change (swipe or tap) slides the new page in from the side it is on
  useEffect(() => {
    const i = TAB_ORDER.indexOf(nav.tab); const d = i - prevIdx.current; prevIdx.current = i;
    if (d === 0) return;
    shift.setValue(d > 0 ? 56 : -56); fade.setValue(0.2);
    Animated.parallel([
      Animated.spring(shift, { toValue: 0, useNativeDriver: true, damping: 20, stiffness: 240, mass: 0.8 }),
      Animated.timing(fade, { toValue: 1, duration: 220, useNativeDriver: true }),
    ]).start();
  }, [nav.tab]);
  const swipe = useRef(PanResponder.create({
    onMoveShouldSetPanResponder: (_, g) => !live.current.open && Math.abs(g.dx) > 26 && Math.abs(g.dx) > Math.abs(g.dy) * 2,
    onPanResponderMove: (_, g) => {
      const i = TAB_ORDER.indexOf(live.current.tab);
      const n = g.dx < 0 ? i + 1 : i - 1;
      const ok = n >= 0 && n < TAB_ORDER.length;
      shift.setValue(Math.max(-90, Math.min(90, g.dx * (ok ? 0.4 : 0.12)))); // the page follows the finger, and resists at the ends
    },
    onPanResponderTerminate: () => { Animated.spring(shift, { toValue: 0, useNativeDriver: true, damping: 18, stiffness: 220 }).start(); },
    onPanResponderRelease: (_, g) => {
      const i = TAB_ORDER.indexOf(live.current.tab);
      const n = g.dx < 0 ? i + 1 : i - 1;
      const far = Math.abs(g.dx) >= 60 || Math.abs(g.vx) >= 0.6;
      if (far && n >= 0 && n < TAB_ORDER.length) {
        Animated.parallel([
          Animated.timing(shift, { toValue: g.dx < 0 ? -80 : 80, duration: 90, useNativeDriver: true }),
          Animated.timing(fade, { toValue: 0, duration: 90, useNativeDriver: true }),
        ]).start(() => live.current.go(TAB_ORDER[n]));
      } else Animated.spring(shift, { toValue: 0, useNativeDriver: true, damping: 18, stiffness: 220 }).start();
    },
  })).current;
  return (
    <View style={{ flex: 1 }} {...swipe.panHandlers}>
      <Backdrop target />
      <Animated.View style={[StyleSheet.absoluteFill, { opacity: fade, transform: [{ translateX: shift }] }]}>
        {nav.tab === 'games' && <Games />}
        {nav.tab === 'recorder' && <Recorder tab />}
        {nav.tab === 'plugins' && <Plugins tab />}
        {nav.tab === 'stats' && <Stats />}
        {nav.tab === 'settings' && <Settings />}
      </Animated.View>
      <TabBar tab={nav.tab} onTab={nav.setTab} />
      {r && (
        <Slide key={r.name + (r.name === 'profile' ? r.pkg : r.name === 'webui' ? r.id : '')}>
          <View style={{ flex: 1 }}>
            <Backdrop />
            {r.name === 'profile' && <Profile pkg={r.pkg} label={r.label} />}
            {r.name === 'setup' && <Setup />}
            {r.name === 'presets' && <Presets />}
            {r.name === 'tools' && <Tools />}
            
            {r.name === 'webui' && <PluginUI id={r.id} />}
            {r.name === 'developer' && <Developer />}
            {r.name === 'settingsEditor' && <SettingsEditor />}
            {r.name === 'apps' && <Apps />}
          </View>
        </Slide>
      )}
    </View>
  );
}

function Themed() {
  const { state } = useStore();
  const base = themeById(state.themeId);
  const accented = state.accent ? withAccent(base, state.accent) : base;
  const theme = state.glass ? withGlass(accented) : accented;
  return (
    <ThemeProvider theme={theme}>
      <GlassProvider>
      <StatusBar style={theme.dark ? 'light' : 'dark'} />
      <ScrollDirProvider>
        <ToastProvider>
          <NavProvider><WithPerms /></NavProvider>
        </ToastProvider>
      </ScrollDirProvider>
      </GlassProvider>
    </ThemeProvider>
  );
}
function WithPerms() {
  const nav = useNav();
  return <PermissionProvider onSetup={() => nav.push({ name: 'setup' })}><Shell /></PermissionProvider>;
}

installCrashHandler();

export default function App() {
  const [crash, setCrash] = useState<string>(() => api.storageGet('crash') ?? '');
  if (crash) return <CrashScreen report={crash} onClose={() => setCrash('')} />;
  return <SafeAreaProvider><StoreProvider><Themed /></StoreProvider></SafeAreaProvider>;
}
