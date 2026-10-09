import { NativeModule, requireOptionalNativeModule } from 'expo';
import type { NativeApi, NativeEvents } from './DensifyNative.types';

declare class DensifyNativeModule extends NativeModule<NativeEvents> {}
type Mod = DensifyNativeModule & NativeApi;

/** null when the native module isn't in the binary (Expo Go, web, Jest). */
export default requireOptionalNativeModule<Mod>('DensifyNative');
