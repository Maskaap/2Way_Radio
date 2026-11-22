# Radio integration (Phase 3 design)

There are two separate features.

## A. Radio ↔ Phone (gateway)

A **gateway** bridges a real radio channel and a PTT Connect channel.

```
Radio users ))) radio/repeater ── audio + PTT line ── Gateway box ── internet ── LiveKit room ── phones
```

- **Hardware:** the simplest universal option is an analogue radio (or the radio's accessory port) connected to a small computer through a USB sound interface, plus a COR/squelch-detect input and a PTT output (serial RTS or GPIO). Commercial RoIP gateways and vendor interfaces (for example a vendor's IP-site or SIP interface) can also be integrated through their **documented** APIs.
- **Software:** a bridge process implements `GatewayAdapter` (`radio-manager/src/gateway/GatewayAdapter.ts`). It joins the channel's LiveKit room as `gateway:<id>`:
  - When the radio's squelch opens, it requests the floor, publishes the radio audio and releases on close.
  - When a phone talks, it keys the radio and plays the audio to it.
  - Every adapter must enforce a radio TX timeout (`MAX_RADIO_TX_MS`).
- The `gateways` table links a gateway to a channel. It is already in the database.

## B. Radio programming & configuration

`radio-manager/` provides the framework. **No drivers are included yet.**

| Step | Module | Notes |
|---|---|---|
| 1–2 Discovery, brand/model | `DriverRegistry.identify()` | Only a driver that **positively** identifies the radio can claim it; otherwise the result is `unsupported` |
| 3 USB / Bluetooth link | `Transport` interface | Implemented per platform |
| 4 Read settings | `RadioDriver.readConfig` | Optional per driver; capabilities are declared, never assumed |
| 5 Backup | `Backup` (raw blob + sha256) | Required before any write |
| 6 Show/edit channels | `RadioChannel` model | |
| 7 Import/export | `VendorToolIntegration` | Exchange files with licensed vendor CPS |
| 8 Safe write | `checkWrite()` | Needs a backup of *this* radio, a typed serial-number confirmation, the same model, TX frequencies inside **your licensed ranges** and power within the licence |
| 9 Register PTT network devices | `radio_devices` table + admin "Radios" tab | Inventory now; discovery later |

### Reality check per brand

- **Motorola, Hytera, Kenwood (pro/commercial), Icom (pro):** programming normally needs the vendor's **CPS** and often a licence or dealer agreement. Many models use proprietary, undocumented, sometimes encrypted protocols. Integration is via exporting/importing files for the vendor CPS, or official SDKs where the vendor grants access.
- **Baofeng and similar consumer radios:** some protocols are documented by open-source projects (e.g. CHIRP). A driver could be built from that documentation, but **only** for the specific models whose protocol is confirmed.
- I will not add a driver that claims to read or program a radio until it has been tested on that model.

### Licensing (South Africa)

- Transmitting on land-mobile frequencies needs a valid **ICASA** radio frequency spectrum licence for those frequencies (except licence-exempt services that follow their own rules). Radios used must also be type-approved.
- The write guard therefore needs your **licensed ranges** (`LicensedRange[]`) and refuses any TX frequency or power outside them. You enter these once per organisation; they will live in the database when Phase 3 is built.
- This is a safety net, not legal advice. Confirm your obligations with ICASA or your radio dealer.

## Android power button (researched)

Android does not deliver `KEYCODE_POWER` to third-party apps; the system handles it (screen off, power menu, assistant). Accessibility services can't filter it either. It therefore **cannot** be offered as a PTT key. The app says so in Settings instead of shipping a fake option.
