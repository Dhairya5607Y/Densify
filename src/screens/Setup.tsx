import React, { useState } from 'react';
import { Pressable, View } from 'react-native';
import { api, useAdb } from '../native/api';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { Btn, Group, Icon, IconName, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { hexA } from '../ui/TabBar';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const METHODS: Record<string, { title: string; desc: string; icon: IconName; go: string; steps: string[] }> = {
  notif: { title: 'Notification Method', desc: "Use Android's Wireless Debugging notification to authorize Densify.", icon: 'notifications', go: 'Start setup',
    steps: ['Enable Developer options', 'Open Wireless Debugging', 'Turn Wireless Debugging on', 'Tap Start setup below', 'Choose “Pair device with pairing code”, then type the code into the Densify notification', 'Return to Densify and check the connection'] },
  split: { title: 'Split-Screen Method', desc: 'Keep Densify visible while you complete the pairing steps.', icon: 'vertical-split', go: 'Start split-screen setup',
    steps: ['Open Android Settings', 'Go to Developer options', 'Open Wireless Debugging', 'Put Densify in split screen', 'Pair with the code and allow the connection', 'Return to Densify'] },
};
const HELP = [
  ["Why can't Densify enable this itself?", 'Wireless Debugging is a protected developer setting. Android requires you to turn it on and approve the pairing once.'],
  ['It disconnects after a restart', 'Android often turns Wireless Debugging off on reboot or when Wi-Fi changes. After setup, Densify grants itself the permission to switch it back on.'],
  ['What does Densify run?', 'Only commands that read and set your display density and the game settings you turn on. Resolution is never touched.'],
];

export function Setup() {
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const [method, setMethod] = useState('notif');
  const [open, setOpen] = useState<number | null>(null);
  const m = METHODS[method];
  const stat = adb.state === 'connected' ? ['Connected', t.c.ok, 'Densify can change your display density.'] : adb.state === 'connecting' ? ['Connecting…', t.c.mu, 'Talking to Android…']
    : adb.state === 'error' ? ['Connection error', t.c.wn, adb.message || "Couldn't reach Wireless Debugging. Turn it on, stay on Wi-Fi, then retry."] : ['Not connected', t.c.mu, 'Set up Wireless Debugging to enable DPI switching.'];

  const check = async () => {
    try {
      await api.connect();
      api.grantSelf().then(() => toast({ title: 'Connected', sub: 'Permissions were granted automatically.' })).catch(() => toast({ title: 'Connected' }));
    } catch (e) { toast({ title: "Couldn't connect", sub: (e as Error).message, err: true, action: { label: 'Retry', run: check } }); }
  };
  const start = async () => {
    if (method === 'notif') { if (!(await ensure('quickNotif'))) return; api.startPairing(); toast({ title: 'Pairing started', sub: 'Open Wireless Debugging, tap “Pair device with pairing code”, then use the notification.' }); api.openSettings('developer'); }
    else { api.openSettings('developer'); toast({ title: 'Split-screen ready', sub: 'Keep Densify on top while Settings opens below.' }); }
  };

  return (
    <Screen footer={<View style={{ padding: 16, backgroundColor: hexA(t.c.bg, 0.94) }}><Btn wide v="pri" label={adb.state === 'connecting' ? 'Checking…' : 'Check connection'} disabled={adb.state === 'connecting'} onPress={check} /></View>}>
      <Header title="Wireless Debugging" sub="Connect Densify to Android" onBack={nav.back} />
      <Surface pad={16}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}><View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: stat[1] as string }} /><T c={stat[1] as string} w="600" style={{ fontSize: 13 }}>{stat[0]}</T></View>
        <T v="sub" c={t.c.tx2} style={{ marginTop: 8, fontSize: 14, lineHeight: 20 }}>{stat[2]}</T>
        {adb.state === 'error' && <View style={{ marginTop: 12, alignSelf: 'flex-start' }}><Btn label="Retry" onPress={check} /></View>}
      </Surface>

      <Section title="Choose a method" />
      <View style={{ gap: 9 }}>
        {Object.entries(METHODS).map(([k, v]) => {
          const on = method === k;
          return (
            <Pressable key={k} onPress={() => setMethod(k)} style={{ flexDirection: 'row', alignItems: 'center', gap: 14, padding: 14, minHeight: 78, borderRadius: Math.min(t.r, 22), borderWidth: 1.5, borderColor: on ? t.c.ac : t.c.bd, backgroundColor: on ? t.c.acs : t.c.sf }}>
              <View style={{ width: 42, height: 42, borderRadius: t.surface === 'hud' ? 2 : 21, backgroundColor: t.c.acs, alignItems: 'center', justifyContent: 'center' }}><Icon name={v.icon} color={on ? t.c.ac : t.c.mu} /></View>
              <View style={{ flex: 1 }}><T>{v.title}</T><T v="sub">{v.desc}</T></View>
              <View style={{ width: 22, height: 22, borderRadius: 11, borderWidth: 2, borderColor: on ? t.c.ac : t.c.mu, alignItems: 'center', justifyContent: 'center' }}>{on && <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: t.c.ac }} />}</View>
            </Pressable>
          );
        })}
      </View>

      <Section title="Steps" />
      <Surface style={{ paddingVertical: 8 }}>
        {m.steps.map((s, i) => (
          <View key={i} style={{ flexDirection: 'row', gap: 14, paddingHorizontal: 16, paddingVertical: 9, alignItems: 'flex-start' }}>
            <View style={{ width: 26, height: 26, borderRadius: t.surface === 'hud' ? 2 : 13, backgroundColor: t.c.acs, alignItems: 'center', justifyContent: 'center' }}><T c={t.c.ac} w="600" style={{ fontSize: 13 }}>{i + 1}</T></View>
            <T style={{ flex: 1, fontSize: 15, fontWeight: '400', paddingTop: 3 }}>{s}</T>
          </View>
        ))}
        <T v="sub" style={{ paddingHorizontal: 16, paddingTop: 6, paddingBottom: 10 }}>Android doesn't let apps switch Wireless Debugging on for you, so this is a one-time manual setup.</T>
      </Surface>
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
        <View style={{ flex: 1 }}><Btn wide v="tonal" label="Open Wireless Debugging" onPress={() => api.openSettings('developer')} /></View>
        <View style={{ flex: 1 }}><Btn wide v="tonal" label={m.go} onPress={start} /></View>
      </View>

      <Section title="Help" />
      <Group>{HELP.map(([q, a], i) => (
        <Pressable key={q} onPress={() => setOpen(open === i ? null : i)} style={{ paddingHorizontal: 16, paddingVertical: 14, borderTopWidth: i ? 0.5 : 0, borderTopColor: t.c.bd2 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}><T style={{ flex: 1, fontSize: 15 }}>{q}</T><Icon name={open === i ? 'expand-less' : 'expand-more'} /></View>
          {open === i && <T v="sub" c={t.c.tx2} style={{ marginTop: 8, fontSize: 14, lineHeight: 20 }}>{a}</T>}
        </Pressable>
      ))}</Group>
    </Screen>
  );
}
