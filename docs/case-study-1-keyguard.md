# Case study 1: The keyguard that never said "present"

*Milestone 2 acceptance, two consecutive fixes. All timings below are real, taken from
the app's own raw-event log pulled off the device before any code was changed.*

## The symptom

Manual acceptance scenario: use an app for 10 s → screen off for 61+ s → unlock →
use it 10 s more. Expected: **+2 openings, ~20 s**. Measured: **+1 opening, ~22 s** —
the time was counted, the second opening was not.

## Evidence pass 1

The pulled Room database showed the real sequence:

| Time | Event |
|---|---|
| t+0.000 s | `SCREEN_OFF`, target app in foreground |
| t+59.814 s | `SCREEN_ON` — **just under the 60 s boundary**, keyguard still up |
| t+59.9…60.3 s | wallet / biometric system windows |
| t+62.366 s | target app re-reported after unlock |

Root cause #1: the service treated `SCREEN_ON` as "screen usable" immediately, although
the keyguard was still covering everything. It therefore resumed the target app at
59.8 s — inside the debounce window — so no new opening was counted, and usage time
even accrued *before* the unlock.

Fix #1: gate reactivation on the keyguard — `SCREEN_ON` while locked keeps the engine
paused; `ACTION_USER_PRESENT` (unlock) resumes it.

## The regression

The retest got *worse*: +1 opening and only +10.5 s — everything after the unlock was
now lost entirely.

Evidence pass 2 delivered the punchline: across the entire recorded history of the
device, **not a single `ACTION_USER_PRESENT` broadcast had ever reached the service.**
On this device (Samsung, fingerprint unlock, always-on display) the broadcast simply
doesn't arrive. Fix #1 had chained recovery to a signal that never fires. The log also
showed an AOD "double blink" — the display turns on, off again, and on again during
wake-up — which would have confused any sequence-based logic anyway.

## The durable fix

Stop assuming any event ordering. Every relevant callback (screen signals, window
events, including *ignored* system windows) now captures a fresh snapshot of
`PowerManager.isInteractive` and `KeyguardManager.isKeyguardLocked`; the screen counts
as usable exactly when `interactive && !keyguardLocked`. No broadcast is a prerequisite
for any other — even an ignored biometric overlay event can serve as the fallback
trigger that re-activates tracking after an unlock.

A regression test replays the exact recorded device timings (59.814 s / 62.366 s);
a second test covers the short screen-off case (no new opening).

## Lessons

- Pull the real event stream before theorizing: both root causes were visible in the
  data and neither matched the first hypothesis
- OEM reality beats API contracts: a documented broadcast that "always" fires on
  unlock can simply be absent on a given device path
- State machines that survive hostile event streams derive state from queryable truth
  (`isInteractive`, `isKeyguardLocked`) instead of event choreography
