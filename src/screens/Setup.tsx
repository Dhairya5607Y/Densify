import React, { useEffect, useRef, useState } from 'react';
import { AppState, Linking, Pressable, TextInput, View } from 'react-native';
import { api, useAdb } from '../native/api';
import type { DeviceInfo } from '../native/api';
import { useStore } from '../store/store';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { Btn, Group, Icon, IconName, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { hexA } from '../ui/TabBar';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const USB = { title: 'From a computer', desc: 'For Android 10 and below, or when Wireless Debugging is unavailable.', icon: 'usb' as IconName, go: 'Show the command',
  steps: ['Turn on USB debugging in Developer options', 'Connect the phone to a computer with adb installed', 'Run: adb tcpip PORT', 'Allow the debugging prompt on the phone and tick Always allow', 'Unplug the cable, then tap Check connection below. Repeat after each restart'] };
const METHODS: Record<string, { title: string; desc: string; icon: IconName; go: string; steps: string[] }> = {
  notif: { title: 'Notification Method', desc: "Use Android's Wireless Debugging notification to authorize Densify.", icon: 'notifications', go: 'Start setup',
    steps: ['Enable Developer options', 'Open Wireless Debugging', 'Turn Wireless Debugging on', 'Tap Start setup below', 'Choose “Pair device with pairing code”, then type the code into the Densify notification', 'Return to Densify and check the connection'] },
  shizuku: { title: 'Shizuku', desc: 'No pairing code and no Wi-Fi needed. Use it if Wireless Debugging gives errors.', icon: 'bolt', go: 'Allow Shizuku access',
    steps: ['Install Shizuku and start it (with Wireless Debugging on Android 11+, or with root)', 'Tap Allow Shizuku below', 'Approve Densify in the Shizuku prompt', 'Densify connects by itself and keeps working after a restart'] },
  root: { title: 'Root', desc: 'Connects through su. No pairing code and no Wi-Fi needed.', icon: 'admin-panel-settings', go: 'Connect with root',
    steps: ['Make sure your phone is rooted (Magisk, KernelSU or similar)', 'Tap Connect with root below', 'Allow Densify in the root manager prompt and tick remember', 'Densify connects by itself and keeps working after a restart'] },
  usb: USB,
  split: { title: 'Split-Screen Method', desc: 'Keep Densify visible while you complete the pairing steps.', icon: 'vertical-split', go: 'Start split-screen setup',
    steps: ['Open Android Settings', 'Go to Developer options', 'Open Wireless Debugging', 'Put Densify in split screen', 'Pair with the code and allow the connection', 'Return to Densify'] },
};
const HELP = [
  ["Why can't Densify enable this itself?", 'Wireless Debugging is a protected developer setting. Android requires you to turn it on and approve the pairing once.'],
  ['It disconnects after a restart', 'Android often turns Wireless Debugging off on reboot or when Wi-Fi changes. After setup, Densify grants itself the permission to switch it back on.'],
  ['What is Shizuku?', 'A free app that lets other apps run shell commands. When it is running, Densify uses it instead of Wireless Debugging, so there is no pairing code and no Wi-Fi is needed. Densify also falls back to it by itself if Wireless Debugging can’t be reached.'],
  ['The pairing code window keeps closing', 'Use the notification method: Densify keeps its own code field open. If no notification appears, use “Pair with port and code” on this screen.'],
  ['What does Densify run?', 'Only commands that read and set your display density and the game settings you turn on. Screen size is only changed when you use Stretch resolution.'],
];

export function Setup() {
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const { state, update } = useStore();
  const [method, setMethodRaw] = useState(state.mode === 'tcpip' ? 'usb' : state.mode === 'shizuku' ? 'shizuku' : state.mode === 'root' ? 'root' : 'notif');
  const setMethod = (k: string) => {
    setMethodRaw(k);
    update((x) => ({ ...x, mode: k === 'usb' ? 'tcpip' : k === 'shizuku' ? 'shizuku' : k === 'root' ? 'root' : (x.mode === 'tcpip' || x.mode === 'shizuku' || x.mode === 'root') ? 'wireless' : x.mode }));
    api.disconnect();
  };
  // The phone is read from the device, and the best method is picked for it: Shizuku when it is ready, adb tcpip on Android 10 and below, Wireless Debugging otherwise.
  const [dev, setDev] = useState<DeviceInfo | null>(null);
  useEffect(() => {
    if (api.sdkInt() < 30 && state.mode !== 'tcpip' && state.mode !== 'shizuku' && state.mode !== 'root') setMethod('usb'); // Android 10 and below has no Wireless Debugging
    api.deviceInfo().then((d) => {
      setDev(d);
    });
  }, []);
  const pick = (k: string) => { setMethod(k); if (k !== 'usb') begin(k); };
  const tried = useRef(false);
  useEffect(() => {
    const sub = AppState.addEventListener('change', (st) => {
      if (st !== 'active') return;
      api.deviceInfo().then(setDev);
      if (adb.state !== 'connected' && tried.current) api.connect().then(() => api.grantSelf().catch(() => {})).catch(() => {});
    });
    return () => sub.remove();
  }, [adb.state]);
  const [pp, setPp] = useState('');
  const [pc, setPc] = useState('');
  const recKey = dev ? (dev.recommended === 'tcpip' ? 'usb' : 'notif') : '';
  const shzText = !dev ? '' : dev.shizuku === 'READY' ? 'Shizuku is running and allowed.' : dev.shizuku === 'NEEDS_PERMISSION' ? 'Shizuku is running. Allow Densify in it.' : dev.shizuku === 'STOPPED' ? 'Shizuku is installed but not running.' : 'Shizuku is not installed.';
  const [open, setOpen] = useState<number | null>(null);
  const [port, setPort] = useState(() => api.storageGet('tcpPort') || '5555');
  const portOk = /^\d{4,5}$/.test(port) && Number(port) >= 1024 && Number(port) <= 65535;
  const onPort = (v: string) => { const d = v.replace(/\D/g, '').slice(0, 5); setPort(d); if (/^\d{4,5}$/.test(d) && Number(d) >= 1024 && Number(d) <= 65535) api.storageSet('tcpPort', d); };
  const m0 = METHODS[method];
  const m = { ...m0, steps: m0.steps.map((x) => x.replace('PORT', portOk ? port : '5555')) };
  const stat = adb.state === 'connected' ? ['Connected', t.c.ok, 'Densify can change your display density.'] : adb.state === 'connecting' ? ['Connecting…', t.c.mu, 'Talking to Android…']
    : adb.state === 'error' ? ['Connection error', t.c.wn, adb.message || "Couldn't reach Wireless Debugging. Turn it on, stay on Wi-Fi, then retry."] : ['Not connected', t.c.mu, api.isPaired() ? 'Already set up. Densify reconnects by itself when Wi-Fi is on. Tap Check connection to try now.' : 'Set up Wireless Debugging to enable DPI switching.'];

  const check = async () => {
    try {
      await api.connect();
      api.grantSelf().then(() => toast({ title: 'Connected', sub: 'Permissions were granted automatically.' })).catch(() => toast({ title: 'Connected' }));
    } catch (e) { toast({ title: "Couldn't connect", sub: (e as Error).message, err: true, action: { label: 'Retry', run: check } }); }
  };
  const start = async () => {
    await begin(method);
  };
  const pair = async () => {
    try {
      await api.pairManual(Number(pp), pc);
      api.grantSelf().catch(() => {});
      setPc('');
      toast({ title: 'Paired and connected' });
    } catch (e) { toast({ title: "Couldn't pair", sub: (e as Error).message, err: true }); }
  };
  const begin = async (k: string) => {
    if (k === 'shizuku') {
      const st = await api.shizukuStatus();
      if (st === 'MISSING') { toast({ title: "Shizuku isn't installed", sub: 'Install it, start it, then come back and tap Allow Shizuku.' }); Linking.openURL('https://shizuku.rikka.app/download/').catch(() => {}); return; }
      if (st === 'STOPPED') { api.shizukuOpen(); toast({ title: 'Start Shizuku first', sub: 'Tap Start in Shizuku, then come back.' }); return; }
      if (st === 'NEEDS_PERMISSION') { await api.shizukuRequest(); toast({ title: 'Allow Densify in Shizuku', sub: 'Approve the prompt, then tap Check connection.' }); return; }
      check();
      return;
    }
    if (k === 'root') { check(); return; }
    if (k === 'usb') { toast({ title: 'On your computer run', sub: `adb tcpip ${portOk ? port : '5555'}` }); return; }
    if (k === 'notif') { if (!(await ensure('quickNotif'))) return; api.startPairing(); toast({ title: 'Pairing started', sub: 'Open Wireless Debugging, tap “Pair device with pairing code”, then use the notification.' }); api.openSettings('developer'); }
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

      {dev && (
        <Surface pad={16} style={{ marginTop: 12 }}>
          <T w="600" style={{ fontSize: 14.5 }}>{`${dev.brand} ${dev.model}`}</T>
          <T v="sub" style={{ marginTop: 2 }}>{`${dev.rom}${dev.romVersion ? ` ${dev.romVersion}` : ''} · Android ${dev.release}`}</T>
          <T v="sub" c={t.c.tx2} style={{ marginTop: 8, fontSize: 13.5, lineHeight: 19 }}>
            {dev.recommended === 'tcpip' ? 'This Android version has no Wireless Debugging. Use Shizuku or “From a computer”.' : 'Wireless Debugging is supported on this phone. The Notification method is the easiest way to connect.'}
          </T>
          <T v="sub" style={{ marginTop: 4 }}>{shzText}</T>
          {dev.wirelessSupported && !dev.wifi && method !== 'shizuku' ? <T v="sub" c={t.c.wn} style={{ marginTop: 8 }}>Wi-Fi is off. Wireless Debugging needs Wi-Fi while you pair.</T> : null}
          {dev.tips.map((x, i) => <T key={i} v="sub" c={t.c.tx2} style={{ marginTop: 6 }}>{`• ${x}`}</T>)}
        </Surface>
      )}

      <Section title="Choose a method" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {Object.entries(METHODS).map(([k, v]) => {
          const on = method === k;
          return (
            <Pressable key={k} onPress={() => { tried.current = true; pick(k); }} style={{ width: '48.5%', padding: 12, minHeight: 84, borderRadius: Math.min(t.r, 20), borderWidth: 1.5, borderColor: on ? t.c.ac : t.c.bd, backgroundColor: on ? t.c.acs : t.c.sf, gap: 8 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                <Icon name={v.icon} color={on ? t.c.ac : t.c.mu} />
                {recKey === k ? <T v="sub" c={t.c.ac} w="600" style={{ fontSize: 10.5 }}>BEST</T> : null}
              </View>
              <T w="600" style={{ fontSize: 14 }}>{v.title.replace(' Method', '')}</T>
            </Pressable>
          );
        })}
      </View>
      <T v="sub" c={t.c.tx2} style={{ marginHorizontal: 6, marginTop: 10 }}>{m.desc}</T>

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
      {method === 'usb' && (
        <Surface pad={14} style={{ marginTop: 12 }}>
          <T w="600" style={{ fontSize: 14.5 }}>Port</T>
          <T v="sub" style={{ marginTop: 2 }}>Any port from 1024 to 65535. Use the same number in the adb command. Densify also tries 5555, 5556 and 5557.</T>
          <View style={{ height: 46, borderRadius: t.rs, borderWidth: 1.5, borderColor: portOk ? t.c.bd2 : t.c.wn, justifyContent: 'center', paddingHorizontal: 14, marginTop: 10 }}>
            <TextInput value={port} onChangeText={onPort} keyboardType="number-pad" maxLength={5} style={{ color: t.c.tx, fontSize: 17, fontFamily: t.mono ? 'monospace' : undefined }} />
          </View>
          {!portOk && <T v="sub" c={t.c.wn} style={{ marginTop: 6 }}>Enter a number from 1024 to 65535.</T>}
        </Surface>
      )}
      {(method === 'notif' || method === 'split') && (
        <Surface pad={14} style={{ marginTop: 12 }}>
          <T w="600" style={{ fontSize: 14.5 }}>Pair with port and code</T>
          <T v="sub" style={{ marginTop: 2 }}>If the notification never appears, open “Pair device with pairing code” in Android and type the port and code it shows.</T>
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 10 }}>
            <View style={{ flex: 1, height: 46, borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, justifyContent: 'center', paddingHorizontal: 14 }}>
              <TextInput value={pp} onChangeText={(v) => setPp(v.replace(/\D/g, '').slice(0, 5))} keyboardType="number-pad" placeholder="Port" placeholderTextColor={t.c.mu} style={{ color: t.c.tx, fontSize: 16 }} />
            </View>
            <View style={{ flex: 1, height: 46, borderRadius: t.rs, borderWidth: 1.5, borderColor: t.c.bd2, justifyContent: 'center', paddingHorizontal: 14 }}>
              <TextInput value={pc} onChangeText={(v) => setPc(v.replace(/\D/g, '').slice(0, 6))} keyboardType="number-pad" placeholder="6-digit code" placeholderTextColor={t.c.mu} style={{ color: t.c.tx, fontSize: 16 }} />
            </View>
          </View>
          <View style={{ marginTop: 10 }}><Btn wide v="tonal" label="Pair" disabled={!(Number(pp) >= 1024 && pc.length === 6)} onPress={pair} /></View>
        </Surface>
      )}
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 12 }}>
        <View style={{ flex: 1 }}><Btn wide v="tonal" label="Developer options" onPress={() => api.openSettings('developer')} /></View>
        <View style={{ flex: 1 }}><Btn wide v="tonal" label={method === 'usb' ? 'Show command' : method === 'shizuku' ? 'Allow Shizuku' : method === 'root' ? 'Connect with root' : 'Start again'} onPress={() => { tried.current = true; start(); }} /></View>
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
