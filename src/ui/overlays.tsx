import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Animated, Easing, KeyboardAvoidingView, Modal, Pressable, StyleSheet, View } from 'react-native';
import { BlurView } from 'expo-blur';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../theme/ThemeContext';
import { Btn, T } from './primitives';

/* ---------- toast ---------- */
export type ToastOpts = { title: string; sub?: string; err?: boolean; action?: { label: string; run: () => void } };
const TC = createContext<(o: ToastOpts) => void>(() => {});
export const useToast = () => useContext(TC);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const [cur, setCur] = useState<ToastOpts | null>(null);
  const a = useRef(new Animated.Value(0)).current;
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const hide = useCallback(() => { Animated.timing(a, { toValue: 0, duration: 180, useNativeDriver: true }).start(() => setCur(null)); }, [a]);
  const show = useCallback((o: ToastOpts) => {
    setCur(o); Animated.timing(a, { toValue: 1, duration: 200, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
    clearTimeout(timer.current); timer.current = setTimeout(hide, o.err ? 6000 : 3200);
  }, [a, hide]);
  return (
    <TC.Provider value={show}>
      {children}
      {cur && (
        <Animated.View pointerEvents="box-none" style={{ position: 'absolute', left: 12, right: 12, bottom: insets.bottom + 92, opacity: a, transform: [{ translateY: a.interpolate({ inputRange: [0, 1], outputRange: [14, 0] }) }] }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', paddingVertical: 10, paddingLeft: 16, paddingRight: 8, borderRadius: Math.min(t.r, 20), overflow: 'hidden', backgroundColor: t.dark ? t.c.sf2 : '#1b222a', borderWidth: 1, borderColor: cur.err ? t.c.wn : t.c.bd2, elevation: 8 }}>
            {t.surface === 'glass' && <BlurView intensity={60} tint="dark" experimentalBlurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />}
            <View style={{ flex: 1 }}>
              <T w="600" c={cur.err ? t.c.wn : t.dark ? t.c.tx : '#f2f5f8'} style={{ fontSize: 14.5 }}>{cur.title}</T>
              {cur.sub ? <T v="sub" c={t.dark ? t.c.mu : '#b9c2cc'}>{cur.sub}</T> : null}
            </View>
            {cur.action ? <Btn v="text" label={cur.action.label} onPress={() => { hide(); cur.action!.run(); }} /> : null}
          </View>
        </Animated.View>
      )}
    </TC.Provider>
  );
}

/* ---------- bottom sheet ---------- */
export function Sheet({ visible, onClose, children }: { visible: boolean; onClose: () => void; children: React.ReactNode }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const a = useRef(new Animated.Value(0)).current;
  const [mounted, setMounted] = useState(visible);
  useEffect(() => {
    if (visible) { setMounted(true); Animated.timing(a, { toValue: 1, duration: 280, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start(); }
    else Animated.timing(a, { toValue: 0, duration: 200, useNativeDriver: true }).start(() => setMounted(false));
  }, [visible]);
  if (!mounted) return null;
  return (
    <Modal transparent visible animationType="none" statusBarTranslucent onRequestClose={onClose}>
      <Animated.View style={[StyleSheet.absoluteFill, { backgroundColor: t.c.scrim, opacity: a }]}><Pressable style={{ flex: 1 }} onPress={onClose} /></Animated.View>
      <KeyboardAvoidingView behavior="padding" style={{ flex: 1, justifyContent: 'flex-end' }} pointerEvents="box-none">
        <Animated.View style={{ maxHeight: '88%', borderTopLeftRadius: t.r + 4, borderTopRightRadius: t.r + 4, overflow: 'hidden', backgroundColor: t.surface === 'glass' ? 'rgba(18,24,32,.55)' : t.c.sf2, borderTopWidth: 1, borderColor: t.c.bd2, paddingBottom: insets.bottom + 16, paddingHorizontal: 16, paddingTop: 10, transform: [{ translateY: a.interpolate({ inputRange: [0, 1], outputRange: [500, 0] }) }] }}>
          {t.surface === 'glass' && <BlurView intensity={t.blur + 20} tint="dark" experimentalBlurMethod="dimezisBlurView" style={StyleSheet.absoluteFill} />}
          <View style={{ width: 36, height: 4, borderRadius: 2, backgroundColor: t.c.bd2, alignSelf: 'center', marginBottom: 14 }} />
          {children}
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
