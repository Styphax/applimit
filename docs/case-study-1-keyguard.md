# Case study 1: The keyguard that never said "present"

During milestone 2 acceptance, a screen-off counting bug required two consecutive
fixes. The timings below come from the app's raw-event log, pulled from the device
before any code was changed.

## The symptom

The manual acceptance scenario was to use an app for 10 s, turn the screen off for
61+ s, unlock it and use the app for another 10 s. The expected result was +2 openings
and ~20 s. The measured result was +1 opening and ~22 s. Usage time was counted, but
the second opening was missing.

## First investigation

The Room database showed this sequence:

| Time | Event |
|---|---|
| t+0.000 s | `SCREEN_OFF`, target app in foreground |
| t+59.814 s | `SCREEN_ON`, just under the 60 s boundary, keyguard still up |
| t+59.9…60.3 s | wallet / biometric system windows |
| t+62.366 s | target app re-reported after unlock |

The service treated `SCREEN_ON` as meaning the screen was usable, even though the
keyguard still covered it. Tracking resumed at 59.8 s, inside the debounce window,
so no new opening was counted. Usage time also accrued before the unlock.

The first fix kept the engine paused when `SCREEN_ON` arrived while the device was
locked. It waited for `ACTION_USER_PRESENT` to resume tracking after unlock.

## The regression

The retest counted +1 opening and only +10.5 s. All usage after unlock was now missing.

Across the device's entire recorded history, no `ACTION_USER_PRESENT` broadcast had
reached the service. On this Samsung device with fingerprint unlock and an always-on
display, the broadcast did not arrive. The first fix had made recovery depend on it.

The log also showed an AOD "double blink": the display turned on, off and on again
during wake-up. That sequence would also have disrupted logic that assumed a fixed
event order.

## The final fix

Every relevant callback now reads `PowerManager.isInteractive` and
`KeyguardManager.isKeyguardLocked` to capture the current state. This includes screen
signals and window events, even those from ignored system windows. The screen is
usable exactly when `interactive && !keyguardLocked`.

No broadcast has to arrive before another. Even an ignored biometric overlay event
can trigger the state check that resumes tracking after unlock.

One regression test replays the recorded device timings (59.814 s / 62.366 s).
A second covers a short screen-off interval, which must not count as a new opening.

## Lessons

Both causes were visible in the event log, and neither matched the first hypothesis.
Reading the device's event stream before proposing a fix exposed what was happening.

A documented unlock broadcast can be absent on a particular device path. Checking
`isInteractive` and `isKeyguardLocked` lets the state machine determine the current
state without depending on broadcasts arriving in a particular order.
