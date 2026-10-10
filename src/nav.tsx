import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { BackHandler } from 'react-native';
import type { Tab } from './ui/TabBar';

export type Route = { name: 'profile'; pkg: string; label: string } | { name: 'setup' } | { name: 'presets' } | { name: 'tools' } | { name: 'scripts' } | { name: 'plugins' } | { name: 'webui'; id: string } | { name: 'developer' } | { name: 'settingsEditor' } | { name: 'apps' };
type Nav = { tab: Tab; setTab: (t: Tab) => void; route: Route | null; push: (r: Route) => void; back: () => void };
const C = createContext<Nav>(null as unknown as Nav);
export const useNav = () => useContext(C);

export function NavProvider({ children }: { children: React.ReactNode }) {
  const [tab, setTab] = useState<Tab>('home');
  const [stack, setStack] = useState<Route[]>([]);
  const push = useCallback((r: Route) => setStack((s) => [...s, r]), []);
  const back = useCallback(() => setStack((s) => s.slice(0, -1)), []);
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (stack.length) { back(); return true; }
      if (tab !== 'home') { setTab('home'); return true; }
      return false;
    });
    return () => sub.remove();
  }, [stack.length, tab, back]);
  return <C.Provider value={{ tab, setTab, route: stack.at(-1) ?? null, push, back }}>{children}</C.Provider>;
}
