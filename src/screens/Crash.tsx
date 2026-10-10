import React from 'react';
import { ScrollView, Share, Text, View } from 'react-native';
import { api } from '../native/api';

/** Shown on the next launch after Densify crashed, with the error ready to share. */
export function CrashScreen({ report, onClose }: { report: string; onClose: () => void }) {
  const btn = (label: string, onPress: () => void, pri?: boolean) => (
    <Text onPress={onPress} style={{ flex: 1, textAlign: 'center', paddingVertical: 14, borderRadius: 12, overflow: 'hidden', fontWeight: '700', backgroundColor: pri ? '#ffffff' : '#2a2d33', color: pri ? '#111' : '#fff' }}>{label}</Text>
  );
  return (
    <View style={{ flex: 1, backgroundColor: '#101114', padding: 20, paddingTop: 56 }}>
      <Text style={{ color: '#fff', fontSize: 24, fontWeight: '700' }}>Densify crashed</Text>
      <Text style={{ color: '#9aa0aa', marginTop: 6, marginBottom: 14 }}>Your profiles are safe. Share this report if the problem keeps happening.</Text>
      <ScrollView style={{ flex: 1, backgroundColor: '#1a1c20', borderRadius: 12, padding: 12 }}>
        <Text selectable style={{ color: '#c9ced6', fontFamily: 'monospace', fontSize: 12 }}>{report}</Text>
      </ScrollView>
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
        {btn('Share', () => Share.share({ message: report }).catch(() => {}))}
        {btn('Continue', () => { api.storageSet('crash', ''); onClose(); }, true)}
      </View>
    </View>
  );
}

let installed = false;
/** Saves any uncaught JS error so the crash screen can show it next time. */
export function installCrashHandler() {
  if (installed) return;
  installed = true;
  const g = globalThis as unknown as { ErrorUtils?: { getGlobalHandler: () => (e: Error, fatal?: boolean) => void; setGlobalHandler: (h: (e: Error, fatal?: boolean) => void) => void } };
  const prev = g.ErrorUtils?.getGlobalHandler();
  g.ErrorUtils?.setGlobalHandler((e, fatal) => {
    try { api.storageSet('crash', `${new Date().toISOString()}${fatal ? ' (fatal)' : ''}\n${e?.message}\n\n${e?.stack ?? ''}`.slice(0, 6000)); } catch { /* ignore */ }
    prev?.(e, fatal);
  });
}
