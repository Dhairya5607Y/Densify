import React, { createContext, useCallback, useContext, useRef, useState } from 'react';
import { AppState, PermissionsAndroid, Platform, View } from 'react-native';
import { api } from '../native/api';
import { useTheme } from '../theme/ThemeContext';
import { Sheet } from '../ui/overlays';
import { Btn, Icon, T } from '../ui/primitives';
import { NEEDS, REQ, Req } from './features';

const have = (r: Req) => r === 'notifications' ? api.hasNotifications() : r === 'usage' ? api.hasUsageAccess() : r === 'adb' ? api.adbConnected() : r === 'overlay' ? api.hasOverlay() : api.isBatteryUnrestricted();
const nextActive = () => new Promise<void>((res) => { const s = AppState.addEventListener('change', (st) => { if (st === 'active') { s.remove(); res(); } }); });

type Ensure = (feature: string) => Promise<boolean>;
const C = createContext<Ensure>(async () => true);
export const useEnsure = () => useContext(C);

/** Asks, in plain language, for whatever a feature needs, then continues once it is granted. */
export function PermissionProvider({ children, onSetup }: { children: React.ReactNode; onSetup: () => void }) {
  const t = useTheme();
  const [req, setReq] = useState<Req | null>(null);
  const answer = useRef<(go: boolean) => void>(() => {});
  const ask = (r: Req) => new Promise<boolean>((res) => { answer.current = res; setReq(r); });

  const ensure = useCallback<Ensure>(async (feature) => {
    for (const r of NEEDS[feature] ?? []) {
      if (have(r)) continue;
      const go = await ask(r);
      setReq(null);
      if (!go) return false;
      if (r === 'adb') { onSetup(); return false; }
      if (r === 'notifications') {
        const g = Number(Platform.Version) >= 33 ? await PermissionsAndroid.request('android.permission.POST_NOTIFICATIONS' as never) : 'granted';
        if (g === 'never_ask_again') { api.openSettings('notifications'); await nextActive(); }
      } else { api.openSettings(r === 'usage' ? 'usage' : r === 'overlay' ? 'overlay' : 'battery'); await nextActive(); }
      if (!have(r)) return false;
    }
    return true;
  }, [onSetup]);

  const info = req ? REQ[req] : null;
  return (
    <C.Provider value={ensure}>
      {children}
      <Sheet visible={!!req} onClose={() => answer.current(false)}>
        {info && (<>
          <View style={{ width: 52, height: 52, borderRadius: t.surface === 'hud' ? 4 : 26, backgroundColor: t.c.acs, alignItems: 'center', justifyContent: 'center', marginBottom: 14 }}><Icon name={info.icon} size={26} color={t.c.ac} /></View>
          <T v="h">{info.title}</T>
          <T v="sub" style={{ marginTop: 6, marginBottom: 20, fontSize: 14, lineHeight: 20 }}>{info.body}</T>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <View style={{ flex: 1 }}><Btn wide v="tonal" label="Not now" onPress={() => answer.current(false)} /></View>
            <View style={{ flex: 1 }}><Btn wide v="pri" label={info.action} onPress={() => answer.current(true)} /></View>
          </View>
        </>)}
      </Sheet>
    </C.Provider>
  );
}
