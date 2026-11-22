import type { Capabilities, RadioDriver, RadioIdentity, Transport } from "./types";

export type Identification =
  | { status: "identified"; driverId: string; identity: RadioIdentity; capabilities: Capabilities }
  | { status: "unsupported"; reason: string; transport: string }
  | { status: "error"; driverId: string; message: string; transport: string };

/**
 * Holds installed drivers and identifies radios. It never guesses: if no installed driver
 * positively identifies the radio, the result is "unsupported".
 */
export class DriverRegistry {
  private drivers: RadioDriver[] = [];

  register(d: RadioDriver): void {
    if (this.drivers.some((x) => x.id === d.id)) throw new Error(`Driver ${d.id} already registered`);
    if (!d.protocolSource?.trim()) throw new Error(`Driver ${d.id} must declare its documented protocol source`);
    this.drivers.push(d);
  }

  list(): ReadonlyArray<Pick<RadioDriver, "id" | "brand" | "models" | "protocolSource">> {
    return this.drivers.map(({ id, brand, models, protocolSource }) => ({ id, brand, models, protocolSource }));
  }

  async identify(t: Transport): Promise<Identification> {
    const candidates = this.drivers.filter((d) => safe(() => d.canHandle(t), false));
    if (candidates.length === 0) {
      return { status: "unsupported", reason: "No installed driver recognises this connection", transport: t.description };
    }
    for (const d of candidates) {
      try {
        await t.open();
        const r = await d.probe(t);
        if (r.matched) return { status: "identified", driverId: d.id, identity: r.identity, capabilities: r.capabilities };
      } catch (e) {
        return { status: "error", driverId: d.id, message: (e as Error).message, transport: t.description };
      } finally {
        await t.close().catch(() => undefined);
      }
    }
    return { status: "unsupported", reason: "Drivers were tried but none positively identified the radio", transport: t.description };
  }

  get(id: string): RadioDriver | undefined {
    return this.drivers.find((d) => d.id === id);
  }
}

function safe<T>(fn: () => T, fallback: T): T {
  try { return fn(); } catch { return fallback; }
}
