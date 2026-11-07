import type { Plugin } from '../store/types';

/**
 * Plugins that ship inside the app. Add entries here (or drop module zips into assets/plugins and list them)
 * and they are offered for one-tap install from the Plugins tab.
 * Intentionally empty: AxManager plugins from magiskmodule.com are added by the maintainer.
 */
export type BundledPlugin = { id: string; name: string; version: string; author: string; desc: string; plugin: Plugin };
export const BUNDLED: BundledPlugin[] = [];
