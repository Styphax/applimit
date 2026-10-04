# Case study 3: Watchdog health checks

During milestone 8 acceptance, the watchdog repeatedly warned about a service that
was still running.

## The symptom

The robustness milestone added a WorkManager watchdog to warn when the detection
service dies. During acceptance, it kept sending "re-enable the accessibility service"
notifications even though the service was confirmed alive and enabled.

## Root cause

The health check used two passive signals: a heartbeat timestamp written by a
60-second coroutine timer inside the service process, and whether at least one
command had been processed since connection.

Both signals can stop updating on an idle, dozing phone. The process pauses, no
events arrive and no commands are processed. These signals could not distinguish
an idle service from a dead one.

## The fix

The watchdog now sends a lightweight `HealthProbe` command through the same serialized
queue that processes detection events. It waits up to two seconds for confirmation.
The service confirms only after processing the command and refreshing the heartbeat.

A connected idle service responds immediately and passes the check. An unbound
service, a missing queue consumer or a stuck processor still triggers a warning.
A successful check automatically clears any existing warning.

Regression tests check both cases: an idle service under doze must not trigger a
warning, and a disconnected service must still trigger one.

## Lesson

The time of the last activity cannot distinguish an idle service from a dead one.
When the monitored system can respond to a request, sending a probe through its
normal work path checks whether it can still process work.
