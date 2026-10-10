import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, StyleSheet, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { api } from './src/native/api';
import { NavProvider, useNav } from './src/nav';
import { StoreProvider, useStore } from './src/store/store';
import { ThemeProvider } from './src/theme/ThemeContext';
import { themeById } from './src/theme/themes';
import { PermissionProvider } from './src/logic/permissions';
import { Backdrop } from './src/ui/Backdrop';
import { ToastProvider } from './src/ui/overlays';
import { ScrollDirProvider } from './src/ui/scroll';
import { TabBar } from './src/ui/TabBar';
import { Games } from './src/screens/Games';
import { Home } from './src/screens/Home';
import { Presets } from './src/screens/Presets';
import { Profile } from './src/screens/Profile';
import { Settings } from './src/screens/Settings';
import { Setup } from './src/screens/Setup';
import { Scripts } from './src/screens/Scripts';
import { Tools } from './src/screens/Tools';
import { Plugins } from './src/screens/Plugins';
import { PluginUI } from './src/screens/PluginUI';
import { Apps } from './src/screens/Apps';
import { Developer } from './src/screens/Developer';
import { SettingsEditor } from './src/screens/SettingsEditor';
import { CrashScreen, installCrashHandler } from './src/screens/Crash';

function Slide({ children }: { children: React.ReactNode }) {
  const a = useRef(new Animated.Value(0)).current;
  useEffect(() => { Animated.timing(a, { toValue: 1, duration: 240, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start(); }, [a]);
  return <Animated.View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, opacity: a, transform: [{ translateX: a.interpolate({ inputRange: [0, 1], outputRange: [28, 0] }) }] }}>{children}</Animated.View>;
}

function Shell() {
  const { state } = useStore();
  const nav = useNav();
  useEffect(() => { if (state.automation && !api.isMonitoring()) api.startMonitoring(); }, []);
  const r = nav.route;
  return (
    <View style={{ flex: 1 }}>
      <Backdrop />
      {nav.tab === 'home' && <Home />}
      {nav.tab === 'games' && <Games />}
      {nav.tab === 'settings' && <Settings />}
      <TabBar tab={nav.tab} onTab={nav.setTab} />
      {r && (
        <Slide key={r.name + (r.name === 'profile' ? r.pkg : r.name === 'webui' ? r.id : '')}>
          <View style={{ flex: 1 }}>
            <Backdrop />
            {r.name === 'profile' && <Profile pkg={r.pkg} label={r.label} />}
            {r.name === 'setup' && <Setup />}
            {r.name === 'presets' && <Presets />}
            {r.name === 'tools' && <Tools />}
            {r.name === 'scripts' && <Scripts />}
            {r.name === 'plugins' && <Plugins />}
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
  const theme = themeById(state.themeId);
  return (
    <ThemeProvider theme={theme}>
      <StatusBar style={theme.dark ? 'light' : 'dark'} />
      <ScrollDirProvider>
        <ToastProvider>
          <NavProvider><WithPerms /></NavProvider>
        </ToastProvider>
      </ScrollDirProvider>
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
