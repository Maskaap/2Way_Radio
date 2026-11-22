import type { Backup, RadioChannel, RadioConfig } from "./types";

/**
 * Frequencies your organisation is licensed to transmit on (e.g. from your ICASA licence).
 * The app refuses to write any TX frequency outside these ranges.
 */
export interface LicensedRange {
  label: string;          // e.g. "ICASA licence 12345 – VHF business"
  fromHz: number;
  toHz: number;
  maxPowerW?: number;
}

export interface WritePlan {
  current: RadioConfig;
  proposed: RadioConfig;
  backup: Backup | null;
  licensed: LicensedRange[];
  /** The user must type the radio's serial number (or model if no serial) to confirm. */
  confirmation: string;
}

export interface ChannelChange {
  index: number;
  kind: "added" | "removed" | "changed";
  fields: string[];
}

export type WriteCheck =
  | { ok: true; changes: ChannelChange[] }
  | { ok: false; errors: string[]; changes: ChannelChange[] };

const FIELDS: (keyof RadioChannel)[] = ["name", "rxHz", "txHz", "mode", "powerW", "ctcssRx", "ctcssTx", "dcsRx", "dcsTx", "colorCode", "timeslot", "talkGroup"];

export function diffChannels(a: RadioChannel[], b: RadioChannel[]): ChannelChange[] {
  const byA = new Map(a.map((c) => [c.index, c]));
  const byB = new Map(b.map((c) => [c.index, c]));
  const out: ChannelChange[] = [];
  for (const [i, c] of byB) {
    const old = byA.get(i);
    if (!old) out.push({ index: i, kind: "added", fields: [] });
    else {
      const f = FIELDS.filter((k) => old[k] !== c[k]);
      if (f.length) out.push({ index: i, kind: "changed", fields: f as string[] });
    }
  }
  for (const i of byA.keys()) if (!byB.has(i)) out.push({ index: i, kind: "removed", fields: [] });
  return out.sort((x, y) => x.index - y.index);
}

/** Validates a write BEFORE anything is sent to the radio. Pure function – easy to test and audit. */
export function checkWrite(p: WritePlan): WriteCheck {
  const errors: string[] = [];
  const changes = diffChannels(p.current.channels, p.proposed.channels);
  const id = p.current.identity;

  if (!p.backup) errors.push("A backup of the radio must be taken before writing.");
  else if ((p.backup.identity.serialNumber ?? p.backup.identity.model) !== (id.serialNumber ?? id.model)) {
    errors.push("The backup belongs to a different radio.");
  }
  const expected = (id.serialNumber ?? id.model).trim();
  if (p.confirmation.trim() !== expected) errors.push(`Type "${expected}" to confirm the write.`);
  if (p.proposed.identity.model !== id.model || p.proposed.identity.brand !== id.brand) {
    errors.push("The proposed configuration is for a different radio model.");
  }
  if (changes.length === 0) errors.push("Nothing to write – no changes.");

  for (const ch of p.proposed.channels) {
    if (ch.txHz == null) continue;
    const range = p.licensed.find((r) => ch.txHz! >= r.fromHz && ch.txHz! <= r.toHz);
    if (!range) {
      errors.push(`Channel ${ch.index} "${ch.name}": TX ${(ch.txHz / 1e6).toFixed(5)} MHz is outside your licensed frequencies.`);
    } else if (range.maxPowerW != null && ch.powerW != null && ch.powerW > range.maxPowerW) {
      errors.push(`Channel ${ch.index} "${ch.name}": ${ch.powerW} W exceeds the ${range.maxPowerW} W licensed for ${range.label}.`);
    }
  }
  return errors.length ? { ok: false, errors, changes } : { ok: true, changes };
}
