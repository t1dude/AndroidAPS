# Dexcom G7 Direct BG Source

**Status: Implemented (2026-09-24), not yet tested on a sensor. Not committed.**

Changes made during implementation:

- Modules are `:cgm:dexcomg7` and `:cgm:dexcomg7:protocol`, not under `:plugins:`. `:appshell` pulls every
  `:plugins:*` module into its multiplatform commonMain, and this driver is Android only. `:app` depends on
  `:cgm:dexcomg7` directly, the same way it gets the pump drivers.
- Pairing tries one sensor at a time (Trio connects to several at once). One GATT client at a time is
  far more reliable on Android; the planner still decides the order.
- New `NotificationId`s `DEXCOM_G7_*` in `:core:interfaces` for the lifecycle alerts.
- CameraX 1.6.2 added to the version catalog for the barcode scanner.
- The plugin opens on the shared BG readings list (view, remove, duplicates marked), like every other
  source. A sensor icon in its toolbar leads to the G7 status, pairing and log screens. For this,
  `BgSourceComposeContent` in `:plugins:source` is public and takes optional extra toolbar actions.

Open question: glucose alarms. AAPS has the missed-readings alarm (off by default) and Automation
(BG / delta triggers + Alarm action). Not decided yet whether to build Dexcom-style alarms (urgent low,
low, high, urgent low soon, rise/fall fast, snooze/repeat) into the plugin.

## Goal

A new BG source, "Dexcom G7 Direct", that talks to a Dexcom G7 or ONE+ sensor over Bluetooth
with no other app on the phone. It covers pairing (by scanning the applicator barcode or typing
the code), readings, backfill, sensor status, lifecycle alerts and calibration.

## Decisions

| # | Question            | Decision                                                                 |
|---|---------------------|--------------------------------------------------------------------------|
| 1 | Handshake           | Trio's own EC-JPAKE + built-in Dexcom credentials. No `libkeks`, no user certificate. Personal use only, not for upstream. |
| 2 | Scope               | Barcode scanning is required. No "read alongside the Dexcom app" mode. No Dexcom Share upload. |
| 3 | Sensor models       | G7 (10 and 15 day) and ONE+. **No Stelo.**                              |
| 4 | Source id           | Reuse `SourceSensor.DEXCOM_G7_NATIVE` (the BYODA one). No database change. |
| 5 | Display slot        | Always `phone` (2). No setting.                                          |

## Sources we take from

- **Trio `G7SensorKit`** (MIT, branch `feat/g7-direct`): protocol, crypto, pairing planner, session
  flow, backfill, calibration, lifecycle, UI flow. Port the tests and their vectors too.
  - Handshake: `G7JPAKE`, `G7Authenticator`, `G7AES`, `G7ChallengeSigner`, `G7DexcomCredentials`
  - Pairing: `G7PairingPlanner`, `G7PairingService`, `G7Advertisement`, `G7SensorPackage`
  - Session: `G7Sensor`, `G7CGMManager` (state, lifecycle, alerts)
  - Messages: `G7GlucoseMessage`, `G7BackfillMessage`, `AlgorithmState`, version and calibration messages
  - UI: `G7UICoordinator`, `G7SettingsView`, onboarding views
- **Watch-APS `plugins/source-ble`**: the Android Bluetooth lessons only (not `libkeks`).
  - One GATT operation at a time (`GattQueue`)
  - Scan filter on the advertised UUID `FEBC` with a mask, not on the GATT service
  - Reconnect to the known address with `autoConnect = true`; scans return nothing in Doze
  - Remember the last address across restarts; after several restarts with no reading, scan again
  - Check permission and location before a scan (a SecurityException there kills the process)
  - Bond state broadcast; remove a bond the sensor no longer accepts; retry delay after status 133
  - Watchdog restart when nothing has arrived for a long time
- Use Trio's glucose packet layout, not Watch-APS's (they differ on the display-only bit and the
  "no value" marker `0xFFFF`; Trio's comes from Loop's long-used G7SensorKit).

## Why not "read alongside the Dexcom app"

Trio keeps an old mode where the Dexcom app owns the sensor and Trio only listens. On Android the
existing BYODA source (`DexcomPlugin`) already covers "use the Dexcom app", so this mode is not needed.

## Modules

### `:plugins:source:dexcomg7:protocol` (pure Kotlin, no Android)

- Messages: glucose `0x4E`, backfill (9-byte records), extended version `0x52`, transmitter
  version `0x4A`, calibrate `0x34`, calibration bounds `0x32`, auth status `0x05`, backfill done `0x59`
- `AlgorithmState`, `G7SensorModel` (G7 and ONE+ only; `DX01` Stelo rejected), advertisement parser + CRC16-XMODEM
- GS1 Data Matrix element parser (pairing code AI 240, serial AI 21, GTIN AI 01)
- Pairing planner (candidate order, retries, give-up rules)
- Lifecycle alert schedule (pure timing)
- Crypto with `java.math.BigInteger` + JCA: P-256 point maths, EC-JPAKE, AES-ECB challenge,
  ECDSA-P256 signature (DER converted to raw r||s)
- Test-only fake sensor that plays the sensor side of the handshake
- Tests: port all Trio tests (JPAKE, auth crypto, glucose/backfill parsing, advertisement,
  package, planner, lifecycle schedule)

### `:plugins:source:dexcomg7` (plugin, Android)

- Bluetooth transport (Phase 2), plugin (Phase 3), Compose UI (Phase 4), alerts (Phase 5)
- Metro wiring; registered as BGSOURCE so it appears in the BG source list

## Phase 2 - Android Bluetooth

- **Transport**: the Watch-APS lessons above, rebuilt on coroutines.
- **Handshake** as `suspend` functions, with channels in place of Trio's blocking buffers:
  1. Subscribe certificate (`...3538`) and authentication (`...3535`)
  2. No stored key: 3 JPAKE rounds (`0A nn`, 160-byte rounds in 20-byte chunks) -> key = first 16 bytes
  3. Challenge `02 + 8 random + display type 02`, check the answer, reply `04 + answer`, read `05` status
  4. After a fresh exchange: certificates `0B`, key challenge `0C` (sign), finalize `06 1E`, bond `07`, wait for `08`
  5. Android shows the system pairing prompt at step 4; handle the bond broadcast
- **Session** after auth: subscribe control + backfill, send `4E`, then after the reply:
  backfill `59 start end` only when the AAPS database has a gap (newest stored reading more than
  7 min before the live one; max 24 h, 3 h if nothing stored), version info once, pending
  calibration, bounds request. Sensor seconds become phone time through one stored clock anchor
  (`clockAnchorAt`), not a value worked out again per connection: that moved by up to a second and
  made the same reading land in the database twice. A connection within 60 s of a reading is
  dropped without a handshake (the sensor still advertises for a moment after a cycle).
- **Key recovery**: challenge mismatch or "no app key" -> drop the stored key and run JPAKE again
  with the stored code. Any other refusal -> stop and alert; never retry into the 4-refusal lockout.
- **Pairing service**: scan up to 20 min, planner-ordered candidates, filter by serial checksum
  when the barcode gave a serial, per-candidate timeouts, keep the authenticated link for the
  session (no gap after pairing).
- **Watchdog**: restart after about 20 min without a packet (count packets, not only stored readings,
  so warmup does not trigger it).

## Phase 3 - Plugin and data

- `DexcomG7DirectPlugin` extends `AbstractBgSourceWithSensorInsertLogPlugin`
- Store only readings where state is OK and not display-only; backfill goes through the same
  `insertCgmSourceData` call (dedupe by timestamp)
- `SourceSensor.DEXCOM_G7_NATIVE`; trend arrow from rate with Trio's thresholds
- Sensor change therapy event from the activation time (when `BgSourceCreateSensorChange` is on)
- `hasSensorError()` true for failed / expired / session ended
- Stored state: pairing code, shared key, address, sensor name, serial, firmware, session length,
  paired at, previous sensor. Code and key are not exportable.

## Phase 4 - Compose UI

- **Pairing wizard**:
  1. Warning if the Dexcom G7 / ONE+ app or xDrip is installed (package check, needs `<queries>`)
  2. Sensor application steps (text)
  3. Bluetooth permission + `BlePreCheck`
  4. **Scan the applicator barcode** (camera) or type the 4-digit code
  5. Pairing progress: candidates, attempt, elapsed time, Bluetooth state, cancel
  6. Success
- **Barcode scanning**: CameraX preview + ZXing (`zxing-core` is already in the catalog) Data
  Matrix decoder; `CAMERA` permission asked only on this screen. Not a Dexcom barcode or no code
  in it -> say so and offer typing. A scanned serial is used to filter candidates.
- **Status screen**: sensor card (state, warmup countdown or time left, last reading),
  connection (connected / waiting for next reading / last connect), details (model, serial, code,
  firmware, session length, paired at), previous sensor, actions (Pair new sensor, Forget sensor,
  Calibrate, Communication log with copy).
- **Calibration dialog** with guidance and result (pending / accepted / refused).

## Phase 5 - Alerts

- Through `NotificationManager`: expires in 24 h, expires in 2 h, expired (12 h grace), session
  ended, sensor failed, connection refused, warmup finished.
- Signal loss: use the existing AAPS missed-readings alarm, do not add a second one.

## Phase 6 - Verification

- JVM unit tests (protocol, crypto vectors, planner, fake-sensor handshake), wizard ViewModel
  test, full build.
- On-sensor checklist (done by the user): first pairing by barcode, first pairing by typed code,
  overnight reconnect in Doze, backfill after going out of range, sensor swap, app restart,
  calibration, Bluetooth off/on, phone reboot.
- No install on devices unless asked. No commits unless asked.
