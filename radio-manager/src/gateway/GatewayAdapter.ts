/**
 * Radio-to-Phone gateway contract (Phase 3).
 *
 * Deployment model: a small always-on computer (e.g. Raspberry Pi / mini PC) sits next to a
 * radio or a vendor RoIP gateway. The bridge process joins the channel's LiveKit room as a
 * participant named "gateway:<id>" and uses an adapter below to move audio + PTT.
 *
 *   radio RX audio ──► adapter.onRadioAudio ──► publish to LiveKit (needs the DB floor, like a phone)
 *   LiveKit audio from phones ──► adapter.keyRadio(true) + adapter.playToRadio(...) ──► radio TX
 *
 * Adapters to implement later:
 *   - "audio-interface": sound-card + COR/VOX detect + serial/GPIO PTT line (works with most analogue radios)
 *   - "vendor-roip": a vendor's documented RoIP / SIP / AIS / SDK interface (per vendor licence)
 */
export interface PcmFrame {
  sampleRate: 48000 | 16000 | 8000;
  channels: 1;
  samples: Int16Array;
}

export interface GatewayStatus {
  online: boolean;
  radioTransmitting: boolean;
  radioReceiving: boolean;   // carrier / COR / squelch open
  detail?: string;
}

export interface GatewayAdapter {
  readonly id: string;
  readonly description: string;
  start(): Promise<void>;
  stop(): Promise<void>;
  /** Called by the adapter whenever the radio receives audio (squelch open). */
  onRadioAudio(cb: (f: PcmFrame) => void): void;
  /** Called by the adapter when the radio's squelch opens / closes. */
  onCarrier(cb: (open: boolean) => void): void;
  /** Key / unkey the radio transmitter. Implementations MUST enforce a TX timeout. */
  keyRadio(on: boolean): Promise<void>;
  playToRadio(f: PcmFrame): void;
  status(): GatewayStatus;
}

/** Hard safety limit every adapter should apply to radio transmissions. */
export const MAX_RADIO_TX_MS = 60_000;
