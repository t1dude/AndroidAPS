# Handover: the watch runs the loop and drives the pump

**Status: planned (2026-10-16). Nothing built yet beyond Dexcom G7 direct-to-watch.**

## Goal

A button on the phone hands the running pump and the loop to the AAPS watch app. The watch then reads
the Dexcom G7 itself (direct-to-watch, done), runs the same AAPS engine as the phone and doses. A
second button hands everything back. The phone never talks to the pump while the watch has it.

## Decisions (user, 2026-10-16)

| # | Question | Decision |
|---|---|---|
| 1 | Engine on the watch | The full AAPS engine (algorithm, IOB/COB, constraints, profile, pump driver), not a new loop. Port Watch-APS's approach. |
| 2 | Pumps | Omnipod Dash, Omnipod 5, Medtrum. CGM: Dexcom G7 (direct-to-watch). |
| 3 | Pump setup on the watch | **Never.** No pairing, activation, priming, cannula, deactivation or patch change on the watch. Those stay on the phone. |
| 4 | Trigger | Manual only: a button on the phone, confirmed on the watch. No automatic handover. |
| 5 | Nightscout | Off on the watch. Records are synced through the phone at handback. |
| 6 | Testing | The user has all three pumps and tests them all. Medtrum is the pump in use now, so it goes first. |
| 7 | Watch-APS | tim2000s agrees to us building on his work. |

## What Watch-APS gives us, and what it does not

Watch-APS (`/Volumes/Code/Code/AndroidAPS/Watch-APS`, branches `watch-standalone` and
`watch-phone-follows`) runs the whole AAPS engine on a watch with no phone: Dash pod and Medtrum
patch, Dexcom ONE+ over Bluetooth. The watch owns the pump for good there.

- **Base**: AAPS 3.4.2.2 with Dagger. We are on 4.0 dev with Metro and KMP. So `:watchapp` is
  **ported, not merged**: its design, its list of watch-only pieces, its tests and `watchctl.py`.
- **Watch-only pieces to port**: alarm-driven loop cycle (`LoopService`, WorkManager is not reliable
  on Wear OS off charger), `WatchKeepAlive`, `SingleControllerPlugin` (refuses to dose unless this
  device owns the pump), `LoopbackTransport` / `LocalDataLayerHelper` (wear UI talking to a local
  engine), `WatchDisabledPlugins` / `WatchRequiredPlugins`, `WatchSensorWatchdog`.
- **Faults found there, to check on 4.0 dev**: command queue stuck after a pod stalls in activation
  (bounded at `PUMP_MAX_BUSY_TIME_IN_SECONDS`), Room `blockingGet` inside a transaction deadlocking on
  the watch's smaller pool, a watch bolus delivered twice through `EventWearToMobile` forwarding, a
  sensor watchdog that scans while the watch dozes.
- **Not taken**: his activation and pairing screens (decision 3), his Nightscout-based phone follower
  (decision 5), his properties-file configuration (our settings come from the phone at handover).

## What a pump session is

The handover moves these. From `WatchLocalKeys` in Watch-APS and the drivers here.

| | Dash | Omnipod 5 | Medtrum |
|---|---|---|---|
| Stored as | `DashStringNonPreferenceKey.PodState` (JSON: LTK, EAP-AKA and message sequence numbers, pod state) | `O5StringNonPreferenceKey.PodState`, same shape, **plus** `RegistrationData` | all `Medtrum*NonKey` values: serial, session token, pump state, current and synced history sequence numbers, patch id, ... |
| Hard part | The sequence numbers move with every message. After the first watch message the phone's copy is stale. | As Dash, plus `RegistrationData` is encrypted with the phone's Android Keystore key. Decrypt on the phone, send over the encrypted data layer, encrypt again with the watch's own key. | No rolling crypto counters (key derived from the serial). The history sequence numbers must travel both ways or history is lost or doubled. The patch takes one connection at a time. |

Rule for all: **after handover the phone must not connect to the pump** until it has the session back.

The watch's own values (pump session while it has it, G7 direct state) must survive an import of
the phone's settings. Watch-APS learned this the hard way (`WatchLocalKeys`).

## Record boundaries (AAPS assumes one pump)

Found in Watch-APS `WatchHistoryImport`. Both the handover snapshot and the handback merge must:

- treat a temporary basal from one side that starts inside the other's as ending it
  (`SyncNsTemporaryBasalTransaction` does this) - correct when it is real, wrong when copied twice;
- remember that the newest profile switch wins, so no stale switch may be copied in after a newer one;
- never let the loop take the other device's glucose reading as the current one;
- keep identities so nothing is added twice.

## Total daily dose: 7 days, both ways (user, 2026-10-16)

Dynamic ISF asks `TddCalculator.calculate(7, allowMissingDays = false)` and `calculate(1, ...)`, plus
the last 24 h. With one of the 7 days missing or incomplete it gets nothing and falls back to profile
ISF. So the TDD history must exist on whichever device runs the loop, from the first cycle.

How AAPS keeps it (`TddCalculatorImpl`): each finished day is stored as a cached TDD row (pump type
`CACHE`). A day not in the cache is calculated from the treatments of that day, and only if every
5 minutes of it has a profile and the boluses and basals are there. The code warns about the trap: a
day calculated from **part** of its data is cached, looks exactly like a complete day, and outlives
the mistake (a change only invalidates forward from that record).

So the transfer must be:

- **Handover (phone to watch)**: the cached TDD rows of the last 7 finished days, **and** the raw
  treatments, basals, extended boluses and the profile switches in force for at least the last
  24 h + DIA (today's partial day, the 24 h value, IOB, COB). The watch imports all of it before its
  loop runs once, so no TDD is calculated or cached from a half-imported state.
- **Handback (watch to phone)**: every treatment the watch made, then on the phone: drop the cached TDD
  rows of every day the handover touched and let them be calculated again from the complete records.
  While the watch has the pump, the phone runs no loop, so it must not cache a TDD for those days.
- A test for each direction: 7 complete days arrive, DynISF gets a TDD on the first cycle, and no day
  is cached from part of its data.

## Phases

### Phase 0 - feasibility, before the real build

- **T1: session transfer to a second phone, per pump.** Debug-only "export pump session" and
  "import pump session". Move a running session to the second phone, **status reads only**, move it
  back. Order: Medtrum (in use, simple session), Dash, Omnipod 5 (Keystore data). A pod or patch near
  its end. Answers: does the pump accept a second device continuing its session?
- **T1 result, Medtrum (2026-10-17)**: passed phone to phone and phone to watch. The watch read the
  patch every 5 minutes for 30 minutes with the screen off (alarm-woken reads, 3-4 s each, 7 of 8 good,
  one refused connect; reads now retry once). The history position (284/284) came back unchanged.
  The session now also carries the 7 daily doses, the running temp basal with its duration, and the
  patch's last basal status; the phone applies the basal through the driver's own update at take in and
  recalculates its daily doses from the day the session left.
- **T2: the engine on the Ultra 2** with a virtual pump: battery over a day, the Samsung freezer
  (battery exemption, see the G7 work), a steady 5-minute cycle overnight.

### Phase 1 - the watch app with two roles

**Design (user chose option A, 2026-10-17, after input from Tim):** one watch app, the engine in its own
module, and the AAPS phone app left as it is. Not the phone app built a second time for the watch:
that needs many changes inside the phone app, which Tim avoided on purpose.

- **`:wear:engine`**, an Android library in the `full` watch app. It holds `EngineGraph`, the watch's
  own Metro root for `AppScope`, built like `AppRootGraph` (the phone), `IosAppGraph` and
  `DesktopAppGraph` already are: the modules contribute their own bindings, the graph adds the few the
  phone app adds itself (`AppAndroidBindings`, `SharedImplBindings`, ...).
- **The companion graph moves to its own scope, `WearScope`.** Metro merges every `AppScope`
  contribution on the classpath into every `AppScope` graph, so with the engine on the classpath the
  companion graph would otherwise take in the whole engine. The G7 core classes are scoped where they
  are used (phone `AppScope`, watch `WearScope`) instead of on the class.
- **Only what the watch uses is in the engine** (user, 2026-10-17): Medtrum, Dash and Omnipod 5 only
  (virtual pump for tests), the loop, its safety limits, profile, insulin, sensitivity. No Nightscout
  or other sync, no other CGM sources, no SMS. Plugins in shared modules that the watch does not use
  are left out of `EngineGraph` with `excludes`, so they are never built, and a fixed list says which
  plugins may run, whatever imported settings ask for.
  Still open (ask the user): automation, autotune and smoothing on the watch.
- **The engine runs only while the watch holds the pump** (Tim's battery lesson: in Watch-APS the
  phone link kept waking and running the whole engine). The graph is built and the plugins started at
  handover, stopped at handback. In companion mode nothing of the engine runs.
- **Same process as the companion, to start with.** The companion keeps the G7 (direct to watch) and
  hands its readings to the engine. The engine's watch plugin talks to the companion in the process
  instead of over Bluetooth to the phone. A separate process can come later if memory or crashes ask
  for it.
- **Its own settings file and database** on the watch, so engine and companion settings never mix.
- **Loop cycle**: on each G7 reading, plus an alarm as a fallback when a reading is late.
- **minSdk 31 for the `full` watch app** (the engine modules need Android 12). The client flavours stay
  at 30.
- **Order**: the engine builds and starts on the watch with the virtual pump, open loop (test T2:
  battery, a steady cycle over a night). Then the Medtrum session is taken into the engine's own
  Medtrum plugin, exactly as phone B did in T1, open loop first. Then low glucose suspend, then closed
  loop (Phase 4). The Medtrum test screen moves into the engine until the engine replaces it.
- **The handover snapshot (built 2026-10-17)**: one zip, sent over a Wear channel (a message is limited
  to about 100 KB). It holds a copy of the phone's database made with `VACUUM INTO` and cut down in SQL
  (`AppRepository.writeHandoverSnapshot`): 30 h of records, what is in force at the start of the window (a
  running temp basal, the last profile switches, temp targets and running modes), 8 days of daily doses, no
  APS results, device status, user entries or food. With it go the phone's exportable settings (as in a
  settings export, so no tokens or device addresses) and the pump session. The watch refuses a snapshot of
  another database version. The engine opens the copy as its own database, applies the settings with the
  phone's `PreferenceImportApplier` (keeping its own pump choice), takes the session and starts in open
  loop. If the engine already runs, the app restarts once to take it in; it restarts at hand back too, so
  the engine stops. The engine writes its start and every loop run (with the battery level) to the test
  log that reaches the phone.

- New module (working name `:watchapp`): the wear companion UI plus the AAPS engine. Installed instead
  of `:wear` on handover-capable watches only (Android 12+, minSdk 31). `:wear` stays as it is for
  everyone else.
- Engine dormant while the watch is a companion. Active only while it holds a handed-over session.
- G7 direct-to-watch becomes the engine's BG source.
- Pump drivers on the watch limited to the running life of a pump: connect, status, temp basal, bolus,
  cancel, suspend/resume, history, alarms. Setup paths removed or blocked.
- The Watch-APS pieces listed above, ported to Metro.

### Phase 2 - the handover protocol (shared, small adapter per pump)

Phone side: each driver gets a **"controlled by the watch"** state that refuses every command and does
not reconnect.

Handover, phone to watch:
1. Checks: watch app ready, battery exempt and above a limit, fresh G7 reading on the watch, pump
   running (not in setup, not faulted), no bolus running, command queue empty.
2. Phone stops its loop and closes the pump connection.
3. Snapshot to the watch: pump session (O5 Keystore data re-wrapped), profile and settings, the cached
   TDD rows of the last 7 days plus the raw records for 24 h + DIA (see "Total daily dose"), BG of the
   last 24 h, temp target, running mode, and **the running temp basal with its duration** (user,
   2026-10-17). A pump that reports a running temp basal does not always report how long it runs
   (Medtrum does not), so without it the receiving device does not know when it ends. Seen in the watch
   test: a 120 min zero temp ran on through the whole handover.
4. Watch imports, does **one status read**, confirms.
5. Only then the phone switches to "controlled by the watch". Any failure before that: cancelled,
   the phone still owns the pump.

Handback: the same in reverse, plus every treatment and pump history record the watch made
(Medtrum's history sequence numbers included), so IOB and Nightscout on the phone are complete.

Watch lost or dead: the phone never takes the pump back by itself. An explicit "take back from the
watch" with a warning that IOB may be incomplete. The watch only sets temp basals of 30 minutes or
less, so a dead watch means profile basal within half an hour.

### Phase 3 - while the watch has the pump

- Bolus, carbs, wizard, temp target, suspend/resume on the watch itself.
- Alarms: pump, CGM, loop not running, and "pump needs changing: hand back to the phone".
- The phone, when near, shows the watch's state and history, read only.

### Phase 4 - proof, per pump

- Code checks that the dose path is the same classes as on the phone (Watch-APS `VERACITY.md` ladder).
- Field: open loop on the watch, then low glucose suspend, then closed loop. Each pump separately.

## Open questions

- Does each pump accept a session continued from another device? (T1)
- Battery and freezer behaviour of the full engine on the Ultra 2. (T2)
- Which 4.0 dev engine modules pull in Android pieces a watch cannot run, and what the APK size is.
