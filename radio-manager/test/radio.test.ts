import { test } from "node:test";
import assert from "node:assert/strict";
import { DriverRegistry } from "../src/registry";
import { checkWrite, diffChannels } from "../src/writeGuard";
import type { Backup, RadioConfig, RadioDriver, Transport } from "../src/types";

const transport = (vid = 0x1234): Transport => ({
  kind: "usb-serial", description: `USB ${vid.toString(16)}`, usbVendorId: vid,
  open: async () => {}, close: async () => {}, write: async () => {}, read: async () => new Uint8Array(),
});

const fakeDriver = (matched: boolean): RadioDriver => ({
  id: "test-driver", brand: "TestBrand", models: ["T1"], protocolSource: "unit test fixture",
  canHandle: (t) => t.usbVendorId === 0x1234,
  probe: async () => matched
    ? { matched: true, identity: { brand: "TestBrand", model: "T1", serialNumber: "SN1" }, capabilities: { identify: true, readConfig: false, writeConfig: false } }
    : { matched: false },
});

test("empty registry reports every radio as unsupported (never pretends)", async () => {
  const r = await new DriverRegistry().identify(transport());
  assert.equal(r.status, "unsupported");
});

test("driver that does not positively match → unsupported", async () => {
  const reg = new DriverRegistry(); reg.register(fakeDriver(false));
  assert.equal((await reg.identify(transport())).status, "unsupported");
});

test("driver that matches → identified with its declared capabilities", async () => {
  const reg = new DriverRegistry(); reg.register(fakeDriver(true));
  const r = await reg.identify(transport());
  assert.equal(r.status, "identified");
  if (r.status === "identified") assert.equal(r.capabilities.writeConfig, false);
  assert.equal((await reg.identify(transport(0x9999))).status, "unsupported");
});

test("drivers must declare a documented protocol source", () => {
  assert.throws(() => new DriverRegistry().register({ ...fakeDriver(true), protocolSource: "" }));
});

const cfg = (txHz: number | null, name = "Ops", powerW = 5): RadioConfig => ({
  identity: { brand: "TestBrand", model: "T1", serialNumber: "SN1" },
  channels: [{ index: 1, name, rxHz: 150_000_000, txHz, mode: "nfm", powerW }],
});
const backup: Backup = { id: "b1", takenAt: new Date().toISOString(), identity: cfg(1).identity, driverId: "test-driver", config: cfg(150_000_000), sha256: "x" };
const licensed = [{ label: "Test licence", fromHz: 149_000_000, toHz: 151_000_000, maxPowerW: 5 }];

test("valid write inside licence with backup and confirmation passes", () => {
  const r = checkWrite({ current: cfg(150_000_000), proposed: cfg(150_500_000, "Ops2"), backup, licensed, confirmation: "SN1" });
  assert.equal(r.ok, true);
  assert.deepEqual(r.changes, [{ index: 1, kind: "changed", fields: ["name", "txHz"] }]);
});

test("write is blocked: no backup, wrong confirmation, TX outside licence, over-power", () => {
  const r = checkWrite({ current: cfg(150_000_000), proposed: cfg(446_000_000, "Ops", 10), backup: null, licensed, confirmation: "yes" });
  assert.equal(r.ok, false);
  if (!r.ok) {
    assert.equal(r.errors.length, 3); // backup, confirmation, out-of-licence (power not checked once out of range)
    assert.match(r.errors.join(" "), /backup/);
    assert.match(r.errors.join(" "), /outside your licensed/);
  }
  const p = checkWrite({ current: cfg(150_000_000), proposed: cfg(150_100_000, "Ops", 25), backup, licensed, confirmation: "SN1" });
  assert.equal(p.ok, false);
  if (!p.ok) assert.match(p.errors[0], /exceeds the 5 W/);
});

test("receive-only channels may be any frequency; empty change set is refused", () => {
  assert.equal(checkWrite({ current: cfg(150_000_000), proposed: cfg(null), backup, licensed, confirmation: "SN1" }).ok, true);
  assert.equal(checkWrite({ current: cfg(150_000_000), proposed: cfg(150_000_000), backup, licensed, confirmation: "SN1" }).ok, false);
  assert.equal(diffChannels([], cfg(1).channels)[0].kind, "added");
});
