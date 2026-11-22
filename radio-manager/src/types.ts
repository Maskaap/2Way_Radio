/**
 * Radio Manager – core contracts (Phase 3 foundation).
 *
 * Principles:
 *  1. Nothing is "supported" unless a driver for that exact brand/model family is installed AND
 *     the driver's probe positively identified the radio. Unknown radios are reported as unsupported.
 *  2. Drivers only use documented protocols / vendor SDKs you are licensed to use.
 *  3. Every write is: backup → diff → licence check → explicit user confirmation → write → verify.
 */

export type LinkKind = "usb-serial" | "usb-hid" | "bluetooth-spp" | "bluetooth-le" | "network";

/** How we physically talk to a radio. Implemented per platform (Node serialport, Android USB host, ...). */
export interface Transport {
  readonly kind: LinkKind;
  readonly description: string;          // e.g. "USB VID 0x1234 PID 0x5678 on /dev/ttyUSB0"
  readonly usbVendorId?: number;
  readonly usbProductId?: number;
  open(): Promise<void>;
  close(): Promise<void>;
  write(data: Uint8Array): Promise<void>;
  read(maxBytes: number, timeoutMs: number): Promise<Uint8Array>;
}

export interface RadioIdentity {
  brand: string;                 // "Motorola", "Hytera", "Kenwood", "Icom", "Baofeng", ...
  model: string;
  firmware?: string;
  serialNumber?: string;
  radioId?: string;              // DMR ID / unit ID
}

/** What a specific driver can actually do for the identified radio. Never assumed. */
export interface Capabilities {
  identify: boolean;
  readConfig: boolean;
  writeConfig: boolean;
  exportFile?: string[];          // file formats the driver can export, e.g. ["csv", "vendor-native"]
  importFile?: string[];
  maxChannels?: number;
  notes?: string;
}

export interface RadioChannel {
  index: number;
  name: string;
  rxHz: number;
  txHz: number | null;            // null = receive-only
  mode: "fm" | "nfm" | "dmr" | "dpmr" | "nxdn" | "p25" | "other";
  powerW?: number;
  ctcssRx?: number; ctcssTx?: number;
  dcsRx?: string; dcsTx?: string;
  colorCode?: number; timeslot?: 1 | 2; talkGroup?: number;
}

export interface RadioConfig {
  identity: RadioIdentity;
  channels: RadioChannel[];
  /** Raw codeplug/vendor blob, kept verbatim so a restore is byte-exact. */
  raw?: Uint8Array;
  settings?: Record<string, unknown>;
}

export interface Backup {
  id: string;
  takenAt: string;                // ISO time
  identity: RadioIdentity;
  driverId: string;
  config: RadioConfig;
  sha256: string;
}

export type ProbeResult =
  | { matched: true; identity: RadioIdentity; capabilities: Capabilities }
  | { matched: false };

/** One driver per brand/model family. Add drivers in src/drivers/. */
export interface RadioDriver {
  readonly id: string;                         // e.g. "baofeng-uv5r-serial"
  readonly brand: string;
  readonly models: string[];
  readonly protocolSource: string;             // where the protocol is documented / which SDK + licence
  /** Cheap check before probing (e.g. USB VID/PID). */
  canHandle(t: Transport): boolean;
  /** Must positively identify the radio; return {matched:false} on any doubt. */
  probe(t: Transport): Promise<ProbeResult>;
  readConfig?(t: Transport): Promise<RadioConfig>;
  writeConfig?(t: Transport, cfg: RadioConfig): Promise<void>;
}

/** Vendor CPS / SDK integration point (e.g. launching licensed CPS with an exported file). */
export interface VendorToolIntegration {
  readonly id: string;
  readonly vendor: string;
  readonly kind: "sdk" | "cps-file-exchange" | "cloud-api";
  available(): Promise<boolean>;
  exportFor?(cfg: RadioConfig): Promise<{ fileName: string; data: Uint8Array }>;
  importFrom?(fileName: string, data: Uint8Array): Promise<RadioConfig>;
}
