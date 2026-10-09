import React, { createContext, useContext, useRef, useState } from 'react';
import { NativeScrollEvent, NativeSyntheticEvent, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const C = createContext<{ dir: 'up' | 'down'; set: (d: 'up' | 'down') => void }>({ dir: 'up', set: () => {} });
export const useScrollDir = () => useContext(C);
export function ScrollDirProvider({ children }: { children: React.ReactNode }) {
  const [dir, set] = useState<'up' | 'down'>('up');
  return <C.Provider value={{ dir, set }}>{children}</C.Provider>;
}

/** Scrolling down turns the tab bar solid; scrolling up (or reaching the top) makes it translucent. */
export function Screen({ children, tab, footer }: { children: React.ReactNode; tab?: boolean; footer?: React.ReactNode }) {
  const insets = useSafeAreaInsets();
  const { set } = useScrollDir();
  const last = useRef(0);
  const onScroll = (e: NativeSyntheticEvent<NativeScrollEvent>) => {
    const y = e.nativeEvent.contentOffset.y;
    const d = y - last.current;
    if (y <= 0) { set('up'); last.current = 0; return; }
    if (Math.abs(d) > 6) { set(d > 0 ? 'down' : 'up'); last.current = y; }
  };
  return (
    <View style={{ flex: 1 }}>
      <ScrollView onScroll={onScroll} scrollEventThrottle={16} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingTop: insets.top + 12, paddingHorizontal: 16, paddingBottom: tab ? 120 + insets.bottom : footer ? 24 : 40 + insets.bottom }}>
        {children}
      </ScrollView>
      {footer}
    </View>
  );
}
