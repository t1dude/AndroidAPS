# Handover: the watch runs the loop and drives the pump

**Status (2026-10-29): the engine runs on the watch and reads the patch through its own Medtrum driver
(first test with the patch, 47 minutes). Nothing here is confirmed as working on the watch beyond what the
watch tests below show.**

- Seen working on the watch: Dexcom G7 direct-to-watch; T1 for Medtrum (see the T1 result); the engine
  started from the snapshot, open loop set at the start, a loop result on every G7 reading (18 of 18 in
  1 h 24 min, test run without a patch on 2026-10-29), about 1.4 % battery per hour for engine, G7 and
  companion together; the test log as a file with its summary. Step 1 with the patch (see "First test with
  the patch").
- Seen working on the phone: step 2's history read again at take in (the end of the temp basal the watch
  threw away came back from the patch, no duplicate).
- Seen not working: WorkManager's keep alive on the watch (13 to 29 minutes late), replaced (decision 10).
  The watch threw away the patch's records: it still had the virtual pump of an earlier test registered.
  Fixed (the phone's pump identity goes along), not yet tested on the watch.
- Next: one more short test with the patch for the pump identity (no "Ignoring pump history record", no
  "wrong pump data" notification, every read in the log), then step 3.
- Not started: T1 for Dash and Omnipod 5, the battery baseline without the engine, the Watch-APS pieces
  (`SingleControllerPlugin`, sensor watchdog), steps 3 to 5, Phase 3.

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

## Decisions (user, 2026-10-29), after the engine ran on the watch

| # | Question | Decision |
|---|---|---|
| 8 | Loop modes on the watch | **Closed loop, LGS, Suspend pump.** The engine refuses every other mode. Open loop stays only as the test stage of steps 1 to 3 (it suggests, it doses nothing), and goes when LGS works. |
| 9 | Notifications on the watch | Decided after the modes, not now: pump alarms, CGM alarms, loop not running, "hand back to the phone" (patch expiry, low reservoir). No open loop suggestions. |
| 10 | Waking the engine | Not an alarm for the loop: without CGM values the loop has nothing to do. The G7 wakes the engine. A **fallback alarm** takes over the keep alive's work when no reading came for 7 minutes, then every 5 minutes. WorkManager's keep alive is not used on the watch. |
| 11 | Files between phone and watch | In their own folder, `handover`, next to `exports` in the AAPS folder. Made when the AAPS folder is chosen, and at app start. |

## Steps to driving the patch (agreed 2026-10-29)

Each step is a watch test before the next.

- **Step 0: the phone cannot drive the patch while the watch has it.** Built 2026-10-29: the Medtrum
  transport refuses every connect while the session is away (both its connect paths, before anything
  touches Bluetooth). Every path to the patch goes through it: service, command queue, keep alive,
  automation and SMS through the queue, the patch screens. Before that only the service and the plugin
  checked. `MovedAwaySessionTest`.
- **Step 1: the engine's own Medtrum driver takes the session.** Built 2026-10-29, tested once with the
  patch (below). The session is taken in before the plugins start, and the engine's pump is `MedtrumPlugin` while
  it holds a session (the virtual pump in a test run). The watch's own test reader is gone (one connection
  at a time). The keep alive's work moved out of `KeepAliveWorker` into `KeepAliveCheck`, which the phone's
  worker and the watch's `EngineKeepAlive` both run; on the watch on every reading, at the start, and from
  the fallback alarm. Open loop: the loop sends nothing. The driver itself does connect: status reads, the
  patch history, the profile when the patch does not have it, the time after a time zone change, as on
  the phone. The open loop notification has no accept action, so nothing doses through it. No pump setup
  screen exists on the watch (decision 3).
  **First test with the patch (2026-10-29, 19:07 to 19:54).** The engine started with `MedtrumPlugin`, open
  loop, the phone's temp basal (2.20 U/h until 19:35:58) came along. 10 loop results in 47 minutes, none
  missed, the keep alive never late. Reads through the driver: the button read at 19:07, then the keep
  alive's (it reads when the last read is 15 minutes old, as on the phone), which the test log did not show;
  it shows every read now, with what asked for it. Handed back with no temp basal running (right: it had
  ended), history 43/43. Battery 2.8 % per hour, against 1.4 without the patch (a short sample).
  **Found:** at 19:45 the watch threw away the patch's record of the temp basal's end ("Ignoring pump history
  record ... Generic AAPS", and a "wrong pump data" notification). The pump AAPS takes records from is
  device state (`ActivePumpType`, `ActivePumpSerialNumber`, `ActivePumpChangeTimestamp`, not exportable), so
  the snapshot did not carry it, and the watch still had the virtual pump of the test on 2026-10-17. From
  step 3 on, every temp basal and bolus of the watch would have been lost from its records, and its IOB
  wrong. Fixed: the snapshot's manifest carries the phone's pump identity and the engine writes it right
  after taking the session in (`HandoverSnapshot.PumpIdentity`, `EngineStartTest`). The phone's time, not
  one made on the watch: the rejected record was of a temp basal from before the handover.
- **Step 2: the records come back from the patch itself.** The minimal part built 2026-10-29: the phone
  keeps its history position at move out (`SyncedSequenceAtMoveOut`, a phone-only setting) and at take in
  reads the patch history again from there. So it gets every record the watch made, keyed by pump id
  (nothing twice), also when the watch was lost and the session is taken in from the move-out file.
  Seen on the phone in the first test with the patch: the end of the temp basal came back, no duplicate.
  Open: how far back the patch history goes. A handover longer than the patch keeps records would lose
  some.
- **Step 3: one supervised command from the watch**: a 0 U/h temp basal for 30 minutes, then cancel. On
  the patch, and in the phone's treatments after hand back.
- **Step 4: LGS on the watch.** Open loop goes then (decision 8).
- **Step 5: closed loop**, with the 30-minute limit for temp basals as an engine constraint.

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
  Run it with "Start engine on watch, keep patch (test)" on the phone's Medtrum page: the snapshot goes to
  the watch without the patch session, so the phone keeps the patch and its loop all night. The watch
  marks this as a test run, starts the engine again after a restart, and stops it with "Stop engine" on
  its Medtrum test screen. (Moving the patch session for T2 would leave the patch with no loop at all.)
  The test log goes to the phone whole, as a file over a Wear channel (a message is limited to about
  100 KB, which held only the last hour), at hand back, at "Stop engine" and on "Send log". On top is a
  summary of the last test: loop runs with and without a result, the longest time without one, late keep
  alives, engine restarts, patch reads, and the battery per hour.
  The phone to phone move ("Move patch session out") was removed on 2026-10-28: T1 for Medtrum is done,
  and the watch is the only target now.

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

- **First loop results on the watch (2026-10-28).** Medtrum session and snapshot to the watch at 22:20,
  back at 22:21. The engine took in 597 settings, profile "Normal", 7 daily doses and the running zero
  temp basal, and set open loop. The loop ran twice on the companion's G7 readings (186 and 177
  falling) and suggested a zero temp. Dynamic ISF had all 7 days from the first run ("7 of 7 days
  cached, 0 calculated", ISF 24 mg/dl), as "Total daily dose" asks. Two patch reads, 3-4 s each, history
  418/418 unchanged there and back. Found in the log, and fixed after it:
  - **The engine cancelled the temp basal at its start.** The phone disables its loop before the move
    out, the snapshot brings that mode, and the running mode check (`RunningModeReconciler`) cancels the
    running temp basal for "loop disabled". The engine set open loop only after that. With the virtual
    pump it only ended the copied temp basal in the watch's database (the algorithm saw no temp while the
    patch ran 0 U/h); with the engine's own Medtrum plugin it would be a real command to the patch at
    every handover. Now `startEngine` sets the engine's mode before the check starts, and when it cannot
    (no profile in the snapshot) the check does not start at all.
  - **The phone's widget updater ran in the engine** and failed on every loop run (the watch has no
    widget manager). Left out of `EngineGraph`; `WatchWidgetUpdater` stands in, as on desktop.
  - **Doubles in the engine's settings were stored as Float** (`SPImpl.putDouble`), so a 0.2 U bolus came
    back to the phone as 0.20000000298023224. Stored as text now, as the editor of `SPImpl` already did.
  - **The watch's reads did not set the patch's last connection**, so it went back with the phone's own
    last read. Set after every good read now, as the phone's service does.
  - **Second watch test (2026-10-28, 23:18-23:46, 28 min)**: no temp basal cancel at the start, no widget
    errors, `last_connection` back as the watch's last read, 7 of 7 patch reads good, battery 73 % to
    72 %. But the loop stayed disabled: the engine set its mode before init had completed, and there is
    no profile until then (`ProfileFunction.getProfile`), so open loop was never set. Fixed: the mode is
    set after `config.initCompleted()`, before the running mode check. `EngineStartTest` checks the order.
  - **Open: the keep alive is late on the watch.** It ran every 5 minutes, then not for 13 minutes
    (23:33-23:46, "KeepAlive fail"), then twice at once. WorkManager is held back while the watch is idle
    with the screen off (the Watch-APS lesson). The loop does not depend on it (G7 readings start the
    loop), but the keep alive reads the pump status and raises the "pump unreachable" alarm. On the
    watch it needs an alarm, as the Medtrum reads have. Part of the alarm-driven loop cycle (Watch-APS
    pieces). Count how often it happens in T2.
  - Still open: the loop asked for a zero temp of 120 min. The watch may only set 30 minutes or less
    (Phase 2, "Watch lost or dead"); nothing enforces that yet. Needed before closed loop.

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
