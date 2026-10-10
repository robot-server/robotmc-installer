package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties.Mode
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.progress.ProgressService
import com.sysbot32.robotmc.installer.progress.ProgressSink
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
    val notice: Boolean = false,
    val failureDetail: String? = null,
    val logTail: String = "",
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
    notice: String? = null,
    private val applicationLog: Path = applicationLogFile(),
    private val logHome: String = System.getProperty("user.home").orEmpty(),
    private val logUserName: String = System.getProperty("user.name").orEmpty(),
) : ProgressSink {
    private val decided = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(
        if (notice != null) {
            SessionState(
                phase = SessionPhase.Finished,
                mode = mode,
                prompt = promptFor(mode),
                status = "",
                statuses = emptyList(),
                completedSteps = totalSteps,
                totalSteps = totalSteps,
                message = notice,
                exitCode = 0,
                declined = false,
                notice = true,
            )
        } else {
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
        }
    )
    val state: StateFlow<SessionState> = this.stateFlow.asStateFlow()

    init {
        this.progress.attach(this)
    }

    fun switchMode(mode: Mode) {
        synchronized(this.stateFlow) {
            val current = this.stateFlow.value
            if (current.phase == SessionPhase.Working || current.mode == mode) {
                return
            }
            this.decided.set(false)
            this.stateFlow.value = SessionState(
                phase = SessionPhase.Confirm,
                mode = mode,
                prompt = promptFor(mode),
                status = "",
                statuses = emptyList(),
                completedSteps = if (mode == Mode.UNINSTALL) current.totalSteps else 0,
                totalSteps = current.totalSteps,
                message = null,
                exitCode = null,
                declined = false,
            )
        }
    }

    fun decline() {
        synchronized(this.stateFlow) {
            val current = this.stateFlow.value
            if (current.phase != SessionPhase.Confirm || !this.decided.compareAndSet(false, true)) {
                return
            }
            this.stateFlow.value = current.copy(phase = SessionPhase.Finished, declined = true, exitCode = 0)
        }
    }

    fun accept() {
        val started = synchronized(this.stateFlow) {
            val current = this.stateFlow.value
            if (current.phase != SessionPhase.Confirm || !this.decided.compareAndSet(false, true)) {
                false
            } else {
                this.stateFlow.value = current.copy(phase = SessionPhase.Working, status = "준비 중...")
                true
            }
        }
        if (!started) {
            return
        }
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
            } catch (after: Throwable) {
                log.error(after) { "afterThrow 실행 중 오류" }
            }
            this.update {
                it.copy(phase = SessionPhase.Finished, message = e.message, exitCode = e.exitStatus)
            }
        } catch (e: Throwable) {
            log.error(e) { e.message }
            val logText = readApplicationLogTail(this.applicationLog)
            this.update {
                it.copy(
                    phase = SessionPhase.Finished,
                    message = GENERIC_FAILURE_MESSAGE,
                    exitCode = GENERIC_FAILURE_EXIT,
                    failureDetail = unexpectedFailureText(
                        e,
                        logText,
                        home = this.logHome,
                        userName = this.logUserName,
                    ),
                    logTail = logTail(logText),
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
        const val GENERIC_FAILURE_MESSAGE = "오류가 발생했어요.\n로그를 첨부해서 제보해 주세요."
        const val GENERIC_FAILURE_EXIT = -1

        fun promptFor(mode: Mode): String = when (mode) {
            Mode.INSTALL -> "서버 접속에 필요한 모드 로더 및 모드를 설치할까요?\n기존 설치 모드는 mods_old로 옮겨져요."
            Mode.UNINSTALL -> "이 설치의 프로필과 게임 폴더를 제거할까요?\n세이브는 그대로 둬요."
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

/** 화면에 붙일 application.log 끝부분의 최대 길이. */
const val APPLICATION_LOG_TAIL_CHARS = 8_000

private const val MAX_UTF8_BYTES_PER_CHAR = 4L

fun applicationLogFile(): Path =
    Path.of(System.getProperty("user.home"), ".robotmc-installer", "logs", "application.log")

/**
 * 로그 텍스트의 끝부분. 없거나 비어 있으면 빈 문자열이고, 길면 끝만 남긴다.
 */
fun logTail(text: String?, maxChars: Int = APPLICATION_LOG_TAIL_CHARS): String {
    if (text.isNullOrEmpty() || maxChars <= 0) {
        return ""
    }
    if (text.length <= maxChars) {
        return text
    }
    return text.takeLast(maxChars)
}

/**
 * 예상하지 못한 실패에서 화면에 보여주고 클립보드에 넣는 문자열.
 * 로그가 없거나 비어 있으면 예외 종류와 메시지만 남긴다.
 */
fun unexpectedFailureText(
    error: Throwable,
    logText: String?,
    maxChars: Int = APPLICATION_LOG_TAIL_CHARS,
    home: String = "",
    userName: String = "",
): String {
    val summary = exceptionSummary(error)
    val tail = logTail(logText, maxChars)
    val shown = if (tail.isEmpty()) summary else "$summary\n\n$tail"
    return maskFailureReport(shown, home, userName)
}

/**
 * 화면과 클립보드에 나가기 전에 홈 경로와 사용자 이름을 가린다.
 * 사용자 이름이 두 글자 이하이면 로그의 다른 단어를 지우지 않도록 그대로 둔다.
 */
fun maskFailureReport(text: String, home: String, userName: String): String {
    var masked = text
    for (secret in homeVariants(home)) {
        masked = masked.replace(secret, "~")
    }
    if (userName.length >= MIN_MASKED_USER_NAME_LENGTH) {
        val pattern = Regex("(?<![0-9A-Za-z_])${Regex.escape(userName)}(?![0-9A-Za-z_])")
        masked = pattern.replace(masked, "<user>")
    }
    return masked
}

private const val MIN_MASKED_USER_NAME_LENGTH = 3

private fun homeVariants(home: String): List<String> {
    if (home.length < 2 || home == "/" || home == "\\") {
        return emptyList()
    }
    return listOf(home, home.replace('\\', '/'), home.replace('/', '\\')).distinct()
        .sortedByDescending { it.length }
}

/**
 * 예상하지 못한 오류 화면에서만 복사할 문자열.
 * [UserException], 성공, 취소, 안내에서는 null.
 */
fun failureClipboardText(state: SessionState): String? {
    if (state.phase != SessionPhase.Finished || state.declined || state.notice) {
        return null
    }
    if (state.exitCode != InstallerSession.GENERIC_FAILURE_EXIT) {
        return null
    }
    if (state.message != InstallerSession.GENERIC_FAILURE_MESSAGE) {
        return null
    }
    return state.failureDetail?.takeIf { it.isNotEmpty() }
}

/**
 * [failureClipboardText]가 돌려준 문자열만 클립보드에 넣는다.
 * 복사 대상이 없으면 쓰지 않는다.
 */
fun copyFailureReport(
    state: SessionState,
    writeClipboard: (String) -> Unit = ::writeSystemClipboard,
): Boolean {
    val text = failureClipboardText(state) ?: return false
    return try {
        writeClipboard(text)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        log.warn(exception) { "클립보드에 복사하지 못했어요." }
        false
    }
}

internal fun writeSystemClipboard(text: String) {
    val selection = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
}

/**
 * [path]의 끝만 읽는다. 파일이 없거나 읽을 수 없으면 null, 비어 있으면 빈 문자열.
 */
fun readApplicationLogTail(path: Path, maxChars: Int = APPLICATION_LOG_TAIL_CHARS): String? {
    if (maxChars <= 0) {
        return ""
    }
    return try {
        if (!Files.isRegularFile(path)) {
            null
        } else {
            val size = Files.size(path)
            if (size == 0L) {
                ""
            } else {
                val bytesToRead = minOf(size, maxChars.toLong() * MAX_UTF8_BYTES_PER_CHAR)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
                    channel.position(size - bytesToRead)
                    val buffer = ByteBuffer.allocate(bytesToRead)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) {
                            break
                        }
                    }
                    val read = ByteArray(buffer.position())
                    buffer.flip()
                    buffer.get(read)
                    String(read, StandardCharsets.UTF_8)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        log.warn(exception) { "application.log 끝부분을 읽지 못했어요." }
        null
    }
}

private fun exceptionSummary(error: Throwable): String {
    val type = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
    val message = error.message
    return if (message.isNullOrEmpty()) type else "$type: $message"
}
