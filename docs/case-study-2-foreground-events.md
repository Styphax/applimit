# Case study 2: The launcher event that never came

*Final device regression after milestone 7. The bug it exposed was older than the
milestone — and had been silently masked by an earlier bug for the project's entire
lifetime.*

## The symptom

The automated on-device regression (previously green) suddenly failed two of three
scenarios on the robustness build:

- Scenario "app switch >60 s": **+1 opening instead of +2, and +81.7 s instead of
  ~20 s** — suspiciously close to the full wall-clock span of the scenario
  (10 s use + 61 s home + 10 s use)
- Scenario "app switch <60 s": the harness timed out waiting for the previous session
  to settle

## Evidence

The pulled event log showed: after the harness pressed HOME, **no launcher window
event arrived at all** — the only signal was Samsung Wallet's transient `FrameLayout`
window, which fires on app transitions without any real app switch. Since a recent
(correct) fix, those transient wallet windows are classified as ignorable overlays.
Ignoring them means: nothing clears the foreground. The engine kept the target app
"in the foreground" through the entire home phase — an 88.4-second session spanning
what should have been three.

The uncomfortable historical insight: before that wallet fix, the transient window had
been (wrongly) tracked as a real app — which *coincidentally ended* the target
session whenever the launcher event went missing. **The old bug had been compensating
for a platform flakiness nobody knew existed.** Every earlier green regression run
had silently relied on it. And the second failing scenario was the same root cause in
disguise: a paused-but-unfinalized session could never finalize because no ticker ran
for unmonitored apps.

## The fix

- **Reconciliation instead of trust**: on every ignored overlay event and periodically
  while any app is considered foreground, the service cross-checks its belief against
  the real accessibility window list (`getWindows()` / `rootInActiveWindow`). Only an
  unambiguous finding corrects the state; a lone transient window stays ignored
- **A 5-second maintenance ticker** for all open sessions (the 1-second enforcement
  ticker stays limited to monitored apps for battery reasons), so orphaned sessions
  finalize even if no further event ever arrives
- The regression harness additionally verifies the real top package after HOME via
  read-only `dumpsys` — hardening the harness without masking the accessibility-side
  issue

Rerun: all three scenarios PASS.

## Lessons

- **Fixing a bug can unmask a second one that the first was compensating for.** The
  only defense is re-running behavioral regression suites after every behavioral
  change — unit tests modeled on assumed event streams cannot catch a platform that
  delivers different streams
- Event delivery on Android (which windows fire `TYPE_WINDOW_STATE_CHANGED`, and when)
  is launcher- and OEM-dependent; robust trackers reconcile against queryable state
- An automated device-in-the-loop harness earns its keep the day it fails
