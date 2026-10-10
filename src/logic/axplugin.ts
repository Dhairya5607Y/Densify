import { unzipSync, strFromU8 } from 'fflate';
import { api } from '../native/api';
import type { Plugin } from '../store/types';

/** Plugins are stored in a folder the shell user can read and write, like AxManager does. */
export const BASE = '/data/local/tmp/densify';
export const dirOf = (id: string) => `${BASE}/plugins/${id}`;
/** Copies the bundled BusyBox (and resetprop) to a place scripts can run it from. */
export async function ensureBusybox() {
  const lib = api.nativeLibDir();
  if (!lib) throw new Error('Not available here.');
  await api.shell(`mkdir -p ${shq(BASE + '/bin')}; for f in busybox resetprop; do if [ -f ${shq(lib)}/lib$f.so ]; then cmp -s ${shq(lib)}/lib$f.so ${shq(BASE)}/bin/$f 2>/dev/null || cp ${shq(lib)}/lib$f.so ${shq(BASE)}/bin/$f; chmod 755 ${shq(BASE)}/bin/$f; fi; done`);
}
/** Runs a script the way AxManager does: BusyBox ash in standalone mode. */
export const bbRun = (script: string) => `ASH_STANDALONE=1 ${shq(BASE + '/bin/busybox')} sh ${shq(script)}`;

const BOOT_FILES = ['post-fs-data.sh', 'service.sh', 'boot-completed.sh'];
const ID_RE = /^[a-zA-Z][a-zA-Z0-9._-]+$/;

/** Single-quote a value for the shell. */
export const shq = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
export function toBase64(b: Uint8Array): string {
  let out = '';
  for (let i = 0; i < b.length; i += 3) {
    const n = (b[i] << 16) | ((b[i + 1] ?? 0) << 8) | (b[i + 2] ?? 0);
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] + (i + 1 < b.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < b.length ? B64[n & 63] : '=');
  }
  return out;
}

export async function pushFile(path: string, data: Uint8Array) {
  const dir = path.slice(0, path.lastIndexOf('/'));
  await api.shell(`mkdir -p ${shq(dir)} && : > ${shq(path)}`);
  const CH = 24_000;
  for (let i = 0; i < data.length; i += CH) {
    await api.shell(`echo ${toBase64(data.subarray(i, i + CH))} | base64 -d >> ${shq(path)}`);
  }
}

export function parseProp(text: string): Record<string, string> {
  const o: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const i = line.indexOf('=');
    if (i > 0 && !line.trim().startsWith('#')) o[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return o;
}

const INSTALLER = (mod: string, tmp: string, skip: boolean) => `
export AXERON=true AXERONVER=10400 BOOTMODE=true
MODPATH=${shq(mod)}; TMPDIR=${shq(tmp)}; ZIPFILE=${shq(tmp + '/module.zip')}
API=$(getprop ro.build.version.sdk); ARCH=$(getprop ro.product.cpu.abi)
case "$ARCH" in arm64*) ARCH=arm64; IS64BIT=true;; x86_64*) ARCH=x64; IS64BIT=true;; x86*) ARCH=x86; IS64BIT=false;; *) ARCH=arm; IS64BIT=false;; esac
ui_print() { echo "$1"; }
abort() { echo "! $1"; exit 1; }
set_perm() { chown "$2.$3" "$1" 2>/dev/null; chmod "$4" "$1" 2>/dev/null; }
set_perm_recursive() { find "$1" -type d -exec chmod "$4" {} + 2>/dev/null; find "$1" -type f -exec chmod "$5" {} + 2>/dev/null; }
rm -rf "$MODPATH"; mkdir -p "$MODPATH"
${skip ? '' : 'cp -r "$TMPDIR/ext/." "$MODPATH/"'}
if [ -f "$TMPDIR/ext/customize.sh" ]; then . "$TMPDIR/ext/customize.sh"; fi
[ -f "$MODPATH/module.prop" ] || cp "$TMPDIR/ext/module.prop" "$MODPATH/module.prop"
chmod -R 755 "$MODPATH" 2>/dev/null
rm -rf "$TMPDIR"
echo "- Done"
`;

export type InstallResult = { plugin: Plugin; log: string };

/** Unpacks an AxManager/KernelSU-style module zip, runs its customize.sh and registers it. */
export async function installZip(zip: Uint8Array, onStep?: (s: string) => void): Promise<InstallResult> {
  const files = unzipSync(zip);
  const names = Object.keys(files).filter((n) => !n.endsWith('/'));
  for (const n of names) if (n.startsWith('/') || n.split('/').includes('..')) throw new Error(`Unsafe path in zip: ${n}`);
  const propFile = files['module.prop'];
  if (!propFile) throw new Error('This zip has no module.prop, so it is not a plugin.');
  const prop = parseProp(strFromU8(propFile));
  if (!ID_RE.test(prop.id ?? '')) throw new Error('module.prop has a missing or invalid id.');
  const ver = Number(prop.axeronPlugin ?? 0);
  if (ver > 10400) throw new Error('This plugin needs a newer plugin API than Densify supports.');

  const id = prop.id;
  const mod = dirOf(id);
  const tmp = `${BASE}/tmp/${id}`;
  onStep?.('Preparing BusyBox');
  await ensureBusybox();
  onStep?.('Copying files');
  await api.shell(`rm -rf ${shq(tmp)}; mkdir -p ${shq(tmp + '/ext')}`);
  await pushFile(`${tmp}/module.zip`, zip);
  for (const n of names) await pushFile(`${tmp}/ext/${n}`, files[n]);
  const custom = files['customize.sh'] ? strFromU8(files['customize.sh']) : '';
  const skip = /^\s*SKIPUNZIP=1/m.test(custom);
  onStep?.('Running installer');
  await pushFile(`${tmp}/install.sh`, new TextEncoder().encode(INSTALLER(mod, tmp, skip)));
  const log = await api.shell(`PATH=${shq(BASE + '/bin')}:$PATH ${bbRun(tmp + '/install.sh')} 2>&1`);
  if (/^! /m.test(log) || !/- Done/.test(log)) throw new Error(log.trim().split('\n').slice(-3).join('\n') || 'The installer did not finish.');

  const plugin: Plugin = {
    id, name: prop.name || id, version: prop.version || '1.0', author: prop.author ?? '', desc: prop.description ?? '', on: true,
    actions: [], hooks: [], dir: mod, versionCode: Number(prop.versionCode ?? 0),
    boots: BOOT_FILES.filter((f) => files[f]), action: !!files['action.sh'], webroot: !!files['webroot/index.html'],
    hasUninstall: !!files['uninstall.sh'],
  };
  return { plugin, log: log.trim() };
}

export async function removeZipPlugin(p: Plugin) {
  if (!p.dir) return;
  if (p.hasUninstall) await api.shell(`${bbRun(p.dir + '/uninstall.sh')} 2>&1`).catch(() => '');
  await api.shell(`rm -rf ${shq(p.dir)}`);
}

export const runAction = (p: Plugin) => api.shell(`cd ${shq(p.dir!)} && AXERON=true PATH=${shq(p.dir + '/system/bin')}:${shq(BASE + '/bin')}:$PATH ${bbRun(p.dir + '/action.sh')} 2>&1`);

/** Boot hooks for a zip plugin, in the form the engine runs them. */
export const bootHooks = (p: Plugin) => (p.boots ?? []).map((f) => ({
  trigger: 'boot' as const, pkg: undefined as string | undefined,
  code: `AXERON=true PATH=${shq(p.dir + '/system/bin')}:${shq(BASE + '/bin')}:$PATH ${bbRun(p.dir + '/' + f)}`,
}));

/** Reads webroot/index.html and inlines local scripts and styles, since the page is shown from memory. */
export async function loadWebroot(p: Plugin): Promise<string> {
  const root = `${p.dir}/webroot`;
  let html = await api.shell(`cat ${shq(root + '/index.html')}`);
  const read = async (rel: string) => (/^[\w./-]+$/.test(rel) && !rel.includes('..') ? api.shell(`cat ${shq(`${root}/${rel.replace(/^\.?\//, '')}`)}`).catch(() => '') : '');
  const scripts = [...html.matchAll(/<script[^>]*\ssrc=["']([^"']+)["'][^>]*>\s*<\/script>/gi)];
  for (const m of scripts) html = html.replace(m[0], `<script>${(await read(m[1])).replace(/<\/script/gi, '<\\/script')}</script>`);
  const links = [...html.matchAll(/<link[^>]*\shref=["']([^"']+\.css)["'][^>]*>/gi)];
  for (const m of links) html = html.replace(m[0], `<style>${await read(m[1])}</style>`);
  return html;
}
