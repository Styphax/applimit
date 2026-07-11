# AppLimit

Screen-time enforcement for Android that actually means it. AppLimit puts per-app daily
limits on **how often** you open an app and **how long** you use it, with different
plans per weekday, shared budgets across app groups, allowed time windows, and a
deliberate-friction unlock flow instead of a toothless "remind me later".

Built end-to-end in roughly one day by an **orchestrated two-agent pipeline** — a
planning/reviewing orchestrator agent and an implementing coding agent — against a
written specification, with human acceptance gates on a real device after every
milestone. If you are here to look at the app, start below. If you are here to see
what disciplined agentic software delivery looks like, jump to
[How this was built](#how-this-was-built) and the [case studies](#case-studies).

## Features

- **Opening limits** (e.g. Gmail: 10 openings/day) and **minute limits**
  (e.g. Twitter/X: 10 minutes/day), per app or per app group with a shared budget
- **Weekday plans**: the week is partitioned into plans (e.g. Mon–Fri vs. weekend),
  each with its own limits and time windows; validated so every day belongs to
  exactly one plan
- **Allowed time windows** per plan (e.g. social media only 09:00–21:00) — outside
  the window a hard block with the next release time, no override
- **Warnings before the wall**: notification at 80 % of the minute budget and at the
  second-to-last opening; a small countdown overlay during the final minute
- **Friction unlock**: when a limit is hit, a full-screen block appears and the app is
  sent home before it becomes visible. After a 5-second countdown you can consciously
  grant yourself an extension — small (+1 minute / +1 opening) or large
  (+5 minutes / +3 openings). Unlimited passes, but every single one is logged and
  visible in the dashboard: the friction count *is* the feedback signal
- **Opening semantics with debounce**: re-entering an app within 60 seconds continues
  the same logical opening, so quick app switches don't eat your budget
- **Statistics dashboard**: day view against limits (granted extensions shown as a
  visible surcharge), 7-day trends per app/group, full friction history
- **Robustness**: foreground anchor service, boot recovery, and a watchdog that
  actively probes the detection pipeline (see case study 3) — built to survive
  One UI's aggressive process management
- **66 unit tests** around a pure-Kotlin usage engine with an injectable clock, plus
  an automated on-device regression harness ([verify-device.ps1](verify-device.ps1))

## Architecture

- **Detection**: an `AccessibilityService` consumes `TYPE_WINDOW_STATE_CHANGED` events,
  combined with screen/unlock broadcasts. Foreground state is *reconciled* against the
  real window list (`getWindows()` / `rootInActiveWindow`) instead of trusting event
  delivery — see case study 2 for why
- **Engine**: `UsageEngine` is pure Kotlin/JVM with an injected `java.time.Clock` —
  every counting rule (debounce boundaries, screen-off finalization, keyguard
  handling, day rollover incl. DST) is unit-tested with simulated time
- **Enforcement**: evaluation per serialized command queue; blocks are rendered as
  `SYSTEM_ALERT_WINDOW` overlays plus an immediate `GLOBAL_ACTION_HOME`, so the target
  app never flashes
- **Persistence**: Room (schema v5, strictly additive migrations), counters flushed
  every 7 seconds and on every session end; sessions and friction events are never
  deleted, which is what makes the dashboard's history trustworthy
- **UI**: Jetpack Compose, Material 3

## Requirements and build

- Device: Android 16 (API 36). Developed and verified on a Samsung Galaxy S25 Ultra
  (One UI 8.5); other launchers/OEMs may surface different accessibility event
  patterns (see case study 2)
- Build: JDK 17 and an Android SDK with platform 36 (`gradlew.bat assembleDebug` /
  `./gradlew assembleDebug`). The wrapper honors `JAVA_HOME`
- Install: sideload via `adb install -r`. Note: Android's "restricted settings" gate
  for accessibility services does **not** apply to adb installs; the in-app onboarding
  guides through all six required permissions and Samsung-specific battery settings
- The included [verify-device.ps1](verify-device.ps1) drives a connected device
  through the counting scenarios via adb and reports PASS/FAIL per scenario

## How this was built

The interesting part of this repository is not the app — it is the delivery process.

- **Spec first.** A complete behavioral specification (goal, precise counting
  semantics, architecture, data model, eight milestones with acceptance criteria,
  decision log) was written and agreed *before* the first line of code. It lives in
  [docs/SPEC.md](docs/SPEC.md) (German — the project's working language)
- **Two agents, clear roles.** An orchestrator agent owned the spec, milestone
  handoffs, evidence collection and device automation; a coding agent (GPT-5.6 class,
  maximum reasoning effort) implemented each milestone in fresh, self-contained runs.
  The human owned decisions and on-device acceptance
- **Milestone gates.** Every milestone ended with unit tests, a build, an install on
  the real device, and a human acceptance walk-through against written criteria.
  Specification changes discovered during acceptance (e.g. dropping the
  commitment-delay feature after trying it) were written back into the spec before
  the next handoff
- **Evidence before fixes.** Every device-side failure was investigated by pulling
  the app's Room database off the device and reconstructing the real event timeline
  *before* touching code. Three of those investigations were interesting enough to
  write up — see below
- **Device-in-the-loop regression.** A PowerShell/adb harness replays the counting
  scenarios on the physical device after behavioral changes. It caught a latent bug
  that every unit test missed (case study 2)

## Case studies

1. **[The keyguard that never said "present"](docs/case-study-1-keyguard.md)** — why
   screen-off counting failed, how the fix regressed, and why the final state machine
   trusts no event ordering
2. **[The launcher event that never came](docs/case-study-2-foreground-events.md)** —
   how fixing one bug unmasked another that an earlier bug had been silently
   compensating for
3. **[Watchdog: probe, don't observe](docs/case-study-3-watchdog.md)** — why passive
   liveness checks produce false alarms on a dozing phone, and the active-probe fix

## Honest limitations

- Personal single-device project: tested on exactly one phone model, sideload only,
  no Play Store policy compliance intended (accessibility-based blockers are
  restricted there)
- No tamper protection: you can still uninstall the app or disable the service —
  by design, this is a friction tool, not a prison
- UI language is German

## License

[MIT](LICENSE)
