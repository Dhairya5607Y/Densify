import React, { createContext, useContext } from 'react';
import { Theme, THEMES } from './themes';

const C = createContext<Theme>(THEMES[0]);
export const ThemeProvider = ({ theme, children }: { theme: Theme; children: React.ReactNode }) => <C.Provider value={theme}>{children}</C.Provider>;
export const useTheme = () => useContext(C);
