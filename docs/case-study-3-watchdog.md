# Case study 3: Watchdog — probe, don't observe

*Milestone 8 acceptance. Short, but the lesson generalizes far beyond Android.*

## The symptom

The robustness milestone added a WorkManager watchdog that warns when the detection
service dies. During acceptance it cried wolf: recurring "re-enable the accessibility
service" notifications while the service was demonstrably alive and enabled.

## Root cause

The health check observed *passive* signals: a heartbeat timestamp written by a
60-second coroutine timer inside the service process, plus "at least one command
processed since connect". Both starve legitimately on an idle, dozing phone — the
process is paused, no events arrive, nothing is processed. To a passive observer,
a healthy-but-idle service and a dead service look identical.

## The fix

The watchdog now **actively probes**: it enqueues a lightweight `HealthProbe` command
into the same serialized queue that processes detection events and waits up to two
seconds for the confirmation, which is only issued after the command was actually
processed and the heartbeat refreshed. A connected idle service answers instantly and
is healthy; an unbound service, a missing queue consumer, or a wedged processor still
raises the alarm. A healthy check auto-clears any existing warning.

Regression tests cover both directions: idle-under-doze must stay silent; a genuinely
disconnected service must still warn.

## Lesson

Liveness detection based on "when did we last see activity?" conflates *idle* with
*dead*. If you can interact with the system you are watching, send a probe through the
same path that real work takes — the answer distinguishes the two states in a way no
passive timestamp can.
