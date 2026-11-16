package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.SystemClock
import com.github.sinlordep.mcpcompanion.run.ProcessCatalog
import com.github.sinlordep.mcpcompanion.run.ProcessId
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.sync.Mutex
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The processes of one project: those `mc_run` starts and those the user starts in the IDE, learnt from the
 * execution events of the project's message bus ([ExecutionEventsListener]). Owns every process listener: a process
 * is released, with its output, when its run tab is closed or reused after it ended, when more than
 * [MAX_ENDED] ended processes are retained, or when the project closes.
 */
@Service(Service.Level.PROJECT)
class ProcessTracker internal constructor(private val project: Project, private val clock: Clock) :
    ProcessCatalog,
    Disposable {

    constructor(project: Project) : this(project, SystemClock)

    private val lock = Any()
    private val sequence = AtomicLong()
    private val records = LinkedHashMap<ProcessId, IdeTrackedProcess>()
    private val byEnvironment = IdentityHashMap<ExecutionEnvironment, IdeTrackedProcess>()
    private val byHandler = IdentityHashMap<ProcessHandler, IdeTrackedProcess>()
    private val adoption = RunningProcessAdoption(project, clock)

    /** Serialises the "is it running?" check and the scheduling of a start, per project. */
    val startLock: Mutex = Mutex()

    override suspend fun processes(): List<TrackedProcess> {
        adoption.adoptOnce(this)
        return synchronized(lock) { records.values.reversed() }
    }

    override suspend fun find(id: ProcessId): TrackedProcess? {
        adoption.adoptOnce(this)
        return synchronized(lock) { records[id] }
    }

    /** The handler of a process this tracker owns; null before it exists or for foreign implementations. */
    internal fun handlerOf(process: TrackedProcess): ProcessHandler? = (process as? IdeTrackedProcess)?.handler

    /**
     * The process [environment] will run. `mc_run` calls this before the IDE sees the environment, so the very first
     * event finds it; the execution manager's schedule event ([byIde]) registers starts made elsewhere and marks the
     * start as announced: from then on the execution manager reports how it ends.
     */
    internal fun scheduled(
        environment: ExecutionEnvironment,
        byIde: Boolean = false,
        requested: RunnerAndConfigurationSettings? = null,
    ): IdeTrackedProcess = synchronized(lock) {
        byEnvironment.getOrPut(environment) {
            register(ProcessDescriptions.starting(environment, clock.now(), requested))
        }.also { if (byIde) it.announced = true }
    }

    internal fun starting(environment: ExecutionEnvironment, handler: ProcessHandler) {
        val record = synchronized(lock) {
            byHandler[handler]?.let { return }
            val record = byEnvironment.remove(environment)
                ?: register(ProcessDescriptions.starting(environment, clock.now()))
            byHandler[handler] = record
            record
        }
        record.attach(handler)
        environment.contentToReuse?.takeIf { it.processHandler === handler }
            ?.let { record.releaseWithContent(it) { release(record) } }
    }

    /**
     * The start of [environment] ended without a process. With [unlessAnnounced] (after a runner returned), only when
     * the execution manager never announced it, because then nothing else will report back.
     */
    internal fun notStarted(environment: ExecutionEnvironment, cause: Throwable?, unlessAnnounced: Boolean = false) {
        val record = synchronized(lock) {
            if (unlessAnnounced && byEnvironment[environment]?.announced != false) return
            byEnvironment.remove(environment)
        } ?: return
        record.notStarted(cause?.message)
    }

    /** A process that was running before tracking began; its earlier output is not available. */
    internal fun adopt(handler: ProcessHandler, snapshot: (ProcessId) -> ProcessSnapshot, content: Disposable?) {
        val record = synchronized(lock) {
            if (byHandler.containsKey(handler)) return
            register(snapshot).also { byHandler[handler] = it }
        }
        record.attach(handler)
        content?.let { record.releaseWithContent(it) { release(record) } }
    }

    private fun register(snapshot: (ProcessId) -> ProcessSnapshot): IdeTrackedProcess {
        val id = ProcessId.of(sequence.incrementAndGet())
        val lifetime = Disposer.newDisposable(this, "mc_run process $id")
        val record = IdeTrackedProcess(id, snapshot(id), lifetime, clock, ::ended)
        records[id] = record
        return record
    }

    private fun ended(record: IdeTrackedProcess) {
        if (record.contentClosed) release(record)
        val surplus = synchronized(lock) {
            val ended = records.values.filter { it.snapshot.state.hasEnded }
            ended.take(maxOf(0, ended.size - MAX_ENDED))
        }
        surplus.forEach(::release)
    }

    private fun release(record: IdeTrackedProcess) {
        synchronized(lock) {
            if (record.released) return
            record.released = true
            records.remove(record.id)
            byHandler.values.remove(record)
            byEnvironment.values.remove(record)
        }
        Disposer.dispose(record.lifetime)
    }

    override fun dispose() {
        synchronized(lock) {
            records.values.forEach { it.released = true }
            records.clear()
            byHandler.clear()
            byEnvironment.clear()
        }
    }

    companion object {
        /** Ended processes kept for `output` and `status` until their tab is closed. */
        const val MAX_ENDED: Int = 20

        fun getInstance(project: Project): ProcessTracker = project.service()
    }
}
