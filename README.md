# AppLimit

AppLimit sets daily limits on how often you open Android apps and how long you use
them. You can set different plans for each weekday, share budgets across app groups
and restrict use to specific time windows. When you reach a limit, extending it
requires a short wait and an explicit choice.

The app was built in roughly one day by two coordinated agents: one planned and
reviewed the work, while the other implemented it. They worked from a written
specification, with human acceptance checks on a real device after every milestone.
[How this was built](#how-this-was-built) describes the process, and the
[case studies](#case-studies) cover three device-side investigations.

## Features

- Set daily opening and minute limits per app or for an app group with a shared
  budget, such as 10 Gmail openings or 10 minutes of Twitter/X per day.
- Divide the week into plans, such as Monday to Friday and weekends, each with its
  own limits and time windows. Validation checks that every day belongs to exactly
  one plan.
- Restrict use to allowed time windows, such as social media from 09:00 to 21:00.
  Outside the window, a block shows the next release time and cannot be overridden.
- Receive warnings at 80 % of the minute budget and at the second-to-last opening.
  A small countdown overlay appears during the final minute.
- When you hit a limit, a full-screen block appears and the target app is sent home
  before it becomes visible. After a 5-second countdown, you can grant yourself a
  small extension (+1 minute / +1 opening) or a large one (+5 minutes / +3 openings).
  Extensions are unlimited. Each is logged in the dashboard so you can see how
  often you chose to extend a limit.
- Re-entering an app within 60 seconds continues the same logical opening. Quick
  app switches do not consume another opening.
- The dashboard shows daily usage against limits, with granted extensions shown
  as a visible surcharge, 7-day trends per app or group and the full friction history.
- A foreground anchor service, boot recovery and a watchdog that actively probes
  the detection pipeline help keep the app running under One UI's aggressive
  process management. Case study 3 covers the watchdog.
- 66 unit tests cover the pure-Kotlin usage engine with an injectable clock. An
  automated on-device regression harness checks device behavior
  ([verify-device.ps1](verify-device.ps1)).

## Architecture

- An `AccessibilityService` combines `TYPE_WINDOW_STATE_CHANGED` events with
  screen/unlock broadcasts to detect foreground activity. It reconciles foreground
  state against the real window list (`getWindows()` / `rootInActiveWindow`) because
  event delivery alone is unreliable. Case study 2 explains why.
- `UsageEngine` is pure Kotlin/JVM with an injected `java.time.Clock`. Every counting
  rule is unit-tested with simulated time, including debounce boundaries,
  screen-off finalization, keyguard handling and day rollover with DST.
- A serialized command queue handles enforcement evaluation. Blocks use
  `SYSTEM_ALERT_WINDOW` overlays and an immediate `GLOBAL_ACTION_HOME` so the target
  app does not flash on screen.
- Room stores the data (schema v5, strictly additive migrations). Counters are
  flushed every 7 seconds and at every session end. Sessions and friction events
  are never deleted, preserving the dashboard's history.
- The UI uses Jetpack Compose and Material 3.

## Requirements and build

- Android 16 (API 36). Developed and verified on a Samsung Galaxy S25 Ultra
  (One UI 8.5). Other launchers and OEMs may produce different accessibility event
  patterns (see case study 2).
- JDK 17 and an Android SDK with platform 36 (`gradlew.bat assembleDebug` /
  `./gradlew assembleDebug`). The wrapper honors `JAVA_HOME`.
- Sideload via `adb install -r`. Android's "restricted settings" gate for
  accessibility services does not apply to adb installs. In-app onboarding guides
  you through all six required permissions and Samsung-specific battery settings.
- [verify-device.ps1](verify-device.ps1) runs counting scenarios on a connected
  device via adb and reports PASS/FAIL for each scenario.

## How this was built

Before coding began, a complete behavioral specification was written and agreed.
It covered the goal, precise counting semantics, architecture, data model and eight
milestones with acceptance criteria, along with a decision log.
[docs/SPEC.md](docs/SPEC.md) is in German, the project's working language.

An orchestrator agent managed the specification, milestone handoffs, evidence
collection and device automation. A coding agent (GPT-5.6 class, maximum reasoning
effort) implemented each milestone in a fresh, self-contained run. The human made
decisions and handled on-device acceptance.

Every milestone ended with unit tests, a build, an install on the real device and
a human acceptance walk-through against written criteria. When acceptance testing
led to a specification change, it was recorded before the next handoff. The
commitment-delay feature, for example, was dropped after trying it.

For every device-side failure, the app's Room database was pulled from the device
to reconstruct the event timeline before changing code. The three case studies
below document three of those investigations.

After behavioral changes, a PowerShell/adb harness replayed the counting scenarios
on the physical device. It caught a latent bug that every unit test missed
(case study 2).

## Case studies

1. [The keyguard that never said "present"](docs/case-study-1-keyguard.md): why
   screen-off counting failed, how the fix regressed and why the final state machine
   does not rely on event ordering.
2. [The launcher event that never came](docs/case-study-2-foreground-events.md):
   how fixing one bug exposed another that the earlier bug had been masking.
3. [Watchdog health checks](docs/case-study-3-watchdog.md): why passive
   liveness checks produce false alarms on a dozing phone and how active probes
   address them.

## Limitations

- This is a personal project, tested on one phone model and installed by sideloading.
  Play Store policy compliance is not intended. Accessibility-based blockers are
  restricted there.
- There is no tamper protection. You can uninstall the app or disable the service.
  It is designed to add friction to your choices.
- The UI is in German.

## License

[MIT](LICENSE)
