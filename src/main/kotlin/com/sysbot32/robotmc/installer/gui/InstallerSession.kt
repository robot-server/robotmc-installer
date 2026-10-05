package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties.Mode
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.progress.ProgressSink
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger { }

enum class SessionPhase {
    Confirm,
    Working,
    Finished,
}

data class SessionState(
    val phase: SessionPhase,
    val mode: Mode,
    val prompt: String,
    val status: String,
    val statuses: List<String>,
    val completedSteps: Int,
    val totalSteps: Int,
    val message: String?,
    val exitCode: Int?,
    val declined: Boolean,
) {
    val fraction: Float
        get() = if (this.totalSteps <= 0) {
            0f
        } else {
            (this.completedSteps.toFloat() / this.totalSteps.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 설치 세션의 상태. 창 없이 확인, 진행, 종료를 옮긴다.
 * 수락하면 [work]를 호출한 스레드에서 끝내고, 진행 보고는 [progress]로 들어온다.
 */
class InstallerSession(
    mode: Mode,
    totalSteps: Int,
    private val progress: ProgressService,
    private val work: () -> Unit,
) : ProgressSink {
    private val decided = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(
        SessionState(
            phase = SessionPhase.Confirm,
            mode = mode,
            prompt = promptFor(mode),
            status = "",
            statuses = emptyList(),
            completedSteps = if (mode == Mode.UNINSTALL) totalSteps else 0,
            totalSteps = totalSteps,
            message = null,
            exitCode = null,
            declined = false,
        )
    )
    val state: StateFlow<SessionState> = this.stateFlow.asStateFlow()

    init {
        this.progress.attach(this)
    }

    fun switchMode(mode: Mode) {
        val current = this.state.value
        if (current.phase == SessionPhase.Working || current.mode == mode) {
            return
        }
        this.decided.set(false)
        this.update {
            SessionState(
                phase = SessionPhase.Confirm,
                mode = mode,
                prompt = promptFor(mode),
                status = "",
                statuses = emptyList(),
                completedSteps = if (mode == Mode.UNINSTALL) it.totalSteps else 0,
                totalSteps = it.totalSteps,
                message = null,
                exitCode = null,
                declined = false,
            )
        }
    }

    fun decline() {
        if (!this.decided.compareAndSet(false, true)) {
            return
        }
        this.update {
            it.copy(phase = SessionPhase.Finished, declined = true, exitCode = 0)
        }
    }

    fun accept() {
        if (!this.decided.compareAndSet(false, true)) {
            return
        }
        this.update { it.copy(phase = SessionPhase.Working, status = "준비 중...") }
        try {
            this.work()
            this.update {
                it.copy(phase = SessionPhase.Finished, message = SUCCESS_MESSAGE, exitCode = 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserException) {
            log.error(e) { e.message }
            try {
                e.afterThrow()
            } catch (after: Exception) {
                log.error(after) { "afterThrow 실행 중 오류" }
            }
            this.update {
                it.copy(phase = SessionPhase.Finished, message = e.message, exitCode = e.exitStatus)
            }
        } catch (e: Exception) {
            log.error(e) { e.message }
            this.update {
                it.copy(
                    phase = SessionPhase.Finished,
                    message = GENERIC_FAILURE_MESSAGE,
                    exitCode = GENERIC_FAILURE_EXIT,
                )
            }
        }
    }

    override fun onStatus(status: String) {
        this.update { it.copy(status = status, statuses = it.statuses + status) }
    }

    override fun onStep(n: Int) {
        this.update { it.copy(completedSteps = it.completedSteps + n) }
    }

    private fun update(block: (SessionState) -> SessionState) {
        synchronized(this.stateFlow) {
            this.stateFlow.value = block(this.stateFlow.value)
        }
    }

    companion object {
        const val SUCCESS_MESSAGE = "완료됐어요."
        const val GENERIC_FAILURE_MESSAGE = "오류가 발생했어요.\n로그 파일을 첨부해서 제보해 주세요."
        const val GENERIC_FAILURE_EXIT = -1

        fun promptFor(mode: Mode): String = when (mode) {
            Mode.INSTALL -> "서버 접속에 필요한 모드 로더 및 모드를 설치할까요?\n기존 설치 모드는 mods_old로 옮겨져요."
            Mode.UNINSTALL -> "설치된 모드 로더 및 모드를 제거할까요?"
        }

        fun actionLabel(mode: Mode): String = when (mode) {
            Mode.INSTALL -> "설치"
            Mode.UNINSTALL -> "제거"
        }

        fun workingTitle(mode: Mode): String = when (mode) {
            Mode.INSTALL -> "설치 중"
            Mode.UNINSTALL -> "제거 중"
        }
    }
}
