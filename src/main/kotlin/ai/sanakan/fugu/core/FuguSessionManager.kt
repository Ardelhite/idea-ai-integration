package ai.sanakan.fugu.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Owns the project's chat tabs ([FuguSession]s) and persists them all into the
 * workspace file, so every tab (transcript + Codex thread id) survives restarts.
 *
 * The tool window mirrors this list as content tabs; adding/closing a tab adds or
 * removes a session here.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "ai.sanakan.fugu.FuguSessionManager",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class FuguSessionManager(private val project: Project) : Disposable, PersistentStateComponent<FuguManagerState> {

    private val sessionList = mutableListOf<FuguSession>()

    /** Live, ordered list of open chat tabs. */
    val sessions: List<FuguSession> get() = sessionList

    /** Creates a new tab with the smallest unused number as its title. */
    fun create(): FuguSession {
        val session = FuguSession(project)
        session.assignTitle(nextNumber().toString())
        sessionList.add(session)
        return session
    }

    /** Ensures at least one tab exists (called when the tool window opens). */
    fun ensureAtLeastOne(): FuguSession {
        sessionList.firstOrNull()?.let { return it }
        return create()
    }

    fun remove(session: FuguSession) {
        if (sessionList.remove(session)) session.dispose()
    }

    /** Drops every tab's Codex process so the next turn relaunches with new config. */
    fun reloadAllTransports() {
        sessionList.forEach { it.reloadTransport() }
    }

    private fun nextNumber(): Int {
        val used = sessionList.mapNotNull { it.title.toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return n
    }

    override fun getState(): FuguManagerState = FuguManagerState().also { st ->
        st.sessions = sessionList.mapTo(mutableListOf()) { it.captureState() }
    }

    override fun loadState(state: FuguManagerState) {
        sessionList.forEach { it.dispose() }
        sessionList.clear()
        state.sessions.forEach { persisted ->
            val session = FuguSession(project)
            session.restoreState(persisted)
            sessionList.add(session)
        }
        // Always present tabs as a clean 1..N sequence.
        sessionList.forEachIndexed { i, s -> s.assignTitle((i + 1).toString()) }
    }

    override fun dispose() {
        sessionList.forEach { it.dispose() }
        sessionList.clear()
    }

    companion object {
        fun getInstance(project: Project): FuguSessionManager =
            project.getService(FuguSessionManager::class.java)
    }
}
