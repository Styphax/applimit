# Case study 2: The launcher event that never came

The final device regression after milestone 7 exposed a bug that had been present
throughout the project. An earlier bug had been masking it.

## The symptom

The automated on-device regression had previously passed. On the robustness build,
two of its three scenarios failed:

- "app switch >60 s" counted +1 opening instead of +2 and +81.7 s instead of ~20 s.
  The measured time was close to the full elapsed duration: 10 s of use, 61 s on the
  home screen and another 10 s of use.
- "app switch <60 s" timed out while the harness waited for the previous session
  to settle.

## Evidence

After the harness pressed HOME, the event log contained no launcher window event.
The only signal came from Samsung Wallet's transient `FrameLayout` window, which
appears during app transitions without a real app switch. A recent fix had correctly
classified these windows as ignorable overlays.

With the wallet event ignored and the launcher event missing, nothing cleared the
foreground state. The engine treated the target app as foreground throughout the
home-screen interval. The log showed an 88.4-second session spanning what should
have been three sessions.

Before the wallet fix, the service had incorrectly tracked the transient window as
a real app. That ended the target session whenever the launcher event was missing.
Earlier regression runs had passed because this bug compensated for the missing
platform event.

The timeout in the second scenario had the same cause. A paused but unfinalized
session could not finalize because no ticker ran for unmonitored apps.

## The fix

The service now checks the real accessibility window list (`getWindows()` /
`rootInActiveWindow`) on every ignored overlay event and periodically while it
considers any app to be foreground. It corrects the state only when the result is
unambiguous. A lone transient window remains ignored.

A 5-second maintenance ticker covers all open sessions so orphaned sessions finalize
even if no further event arrives. The 1-second enforcement ticker remains limited
to monitored apps to save battery.

The regression harness also checks the actual top package after HOME using read-only
`dumpsys`. This improves the harness without hiding the accessibility event problem.

All three scenarios passed on the rerun.

## Lessons

Fixing one bug exposed another that it had been compensating for. Behavioral
regression checks need to run after every behavioral change. Unit tests based on
assumed event streams cannot catch a platform that delivers different ones. In this
case, the automated device harness caught what those tests missed.

Which windows emit `TYPE_WINDOW_STATE_CHANGED`, and when they emit it, depends on
the Android launcher and OEM. Reconciling events with queryable state lets the
tracker correct its view when an event is missing.
