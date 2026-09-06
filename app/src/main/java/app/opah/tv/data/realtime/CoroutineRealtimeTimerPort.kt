package app.opah.tv.data.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Process-scoped, monotonic coroutine timers keyed by reducer operation ID. */
class CoroutineRealtimeTimerPort(
    private val scope: CoroutineScope,
    private val events: RealtimeEventSink,
) : RealtimeTimerPort {
    private val lock = Any()
    private val jobs = mutableMapOf<RealtimeOperationId, Job>()

    override fun schedule(command: ScheduleRealtimeTimer) {
        if (command.delayMillis < 0L) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(command.delayMillis)
                events.dispatch(
                    RealtimeTransportEvent.TimerFired(
                        generation = command.generation,
                        operationId = command.operationId,
                    ),
                )
            } finally {
                val runningJob = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (jobs[command.operationId] === runningJob) {
                        jobs.remove(command.operationId)
                    }
                }
            }
        }
        synchronized(lock) {
            jobs.put(command.operationId, job)?.cancel()
        }
        job.start()
    }

    override fun cancel(command: CancelRealtimeTimer) {
        synchronized(lock) {
            jobs.remove(command.operationId)
        }?.cancel()
    }

    fun cancelAll() {
        val pending = synchronized(lock) {
            jobs.values.toList().also { jobs.clear() }
        }
        pending.forEach(Job::cancel)
    }
}
