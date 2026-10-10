package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties.Mode
import com.sysbot32.robotmc.installer.prelaunch.LAUNCHER_RESTART_DETAIL
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.progress.ProgressService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallerSessionTest {
    @Test
    fun declineDoesNotRunWork() {
        var ran = false
        val session = openSession { _ -> ran = true }

        session.decline()

        assertFalse(ran)
        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertTrue(state.declined)
        assertEquals(0, state.exitCode)
        assertNull(state.message)
        assertEquals(0, state.completedSteps)
        assertNoFailureReport(state)
    }

    @Test
    fun promptsMatchTheMode() {
        val install = openSession(mode = Mode.INSTALL)
        val uninstall = openSession(mode = Mode.UNINSTALL)

        assertEquals(
            "서버 접속에 필요한 모드 로더 및 모드를 설치할까요?\n기존 설치 모드는 mods_old로 옮겨져요.",
            install.state.value.prompt,
        )
        assertEquals(
            "이 설치의 프로필과 게임 폴더를 제거할까요?\n세이브는 그대로 둬요.",
            uninstall.state.value.prompt,
        )
        assertEquals(SessionPhase.Confirm, install.state.value.phase)
        assertEquals(SessionPhase.Confirm, uninstall.state.value.phase)
    }

    @Test
    fun acceptedRunRecordsStatusAndProgressThenFinishes() {
        lateinit var session: InstallerSession
        session = openSession(totalSteps = 5) { progress ->
            assertEquals(SessionPhase.Working, session.state.value.phase)

            progress.setStatus("모드 폴더 준비 중...")
            assertEquals("모드 폴더 준비 중...", session.state.value.status)
            assertEquals(listOf("모드 폴더 준비 중..."), session.state.value.statuses)
            assertEquals(0, session.state.value.completedSteps)

            progress.step("모드 다운로드 중: iris.jar")
            assertEquals(1, session.state.value.completedSteps)
            assertEquals("모드 다운로드 중: iris.jar", session.state.value.status)

            progress.setStatus("서버 추가 중: Robot Server")
            progress.step(2)
            assertEquals(3, session.state.value.completedSteps)
            assertEquals(
                listOf(
                    "모드 폴더 준비 중...",
                    "모드 다운로드 중: iris.jar",
                    "서버 추가 중: Robot Server",
                ),
                session.state.value.statuses,
            )
        }

        session.accept()

        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertEquals("완료됐어요.", state.message)
        assertEquals(0, state.exitCode)
        assertFalse(state.declined)
        assertEquals(3, state.completedSteps)
        assertEquals(3f / 5f, state.fraction)
        assertNoFailureReport(state)
    }

    @Test
    fun uninstallStartsFullAndShrinksTowardZero() {
        lateinit var session: InstallerSession
        session = openSession(mode = Mode.UNINSTALL, totalSteps = 4) { progress ->
            assertEquals(4, session.state.value.completedSteps)
            assertEquals(1f, session.state.value.fraction)
            progress.setStatus("모드 삭제 중: iris.jar")
            progress.step(-1)
            assertEquals(3, session.state.value.completedSteps)
            assertEquals("모드 삭제 중: iris.jar", session.state.value.status)
            progress.step(-2)
            assertEquals(1, session.state.value.completedSteps)
        }

        assertEquals(4, session.state.value.completedSteps)
        session.accept()

        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertEquals("완료됐어요.", state.message)
        assertEquals(1, state.completedSteps)
        assertEquals(1f / 4f, state.fraction)
        assertNoFailureReport(state)
    }

    @Test
    fun switchModeChangesTheConfirmScreen() {
        val session = openSession(totalSteps = 4)

        session.switchMode(Mode.UNINSTALL)

        val removed = session.state.value
        assertEquals(SessionPhase.Confirm, removed.phase)
        assertEquals(Mode.UNINSTALL, removed.mode)
        assertEquals(
            "이 설치의 프로필과 게임 폴더를 제거할까요?\n세이브는 그대로 둬요.",
            removed.prompt,
        )
        assertEquals(4, removed.completedSteps)
        assertEquals(1f, removed.fraction)

        session.switchMode(Mode.INSTALL)

        val installed = session.state.value
        assertEquals(Mode.INSTALL, installed.mode)
        assertEquals(0, installed.completedSteps)
        assertTrue(installed.prompt.contains("설치할까요?"))
    }

    @Test
    fun switchModeIsIgnoredWhileWorkingAndResetsAfterFinish() {
        var runs = 0
        lateinit var session: InstallerSession
        session = openSession { _ ->
            runs += 1
            if (runs == 1) {
                session.switchMode(Mode.UNINSTALL)
                assertEquals(Mode.INSTALL, session.state.value.mode)
                assertEquals(SessionPhase.Working, session.state.value.phase)
            }
        }

        session.accept()
        assertEquals(1, runs)
        assertEquals(SessionPhase.Finished, session.state.value.phase)

        session.switchMode(Mode.UNINSTALL)
        assertEquals(SessionPhase.Confirm, session.state.value.phase)
        assertEquals(Mode.UNINSTALL, session.state.value.mode)

        session.accept()
        assertEquals(2, runs)
        assertEquals("완료됐어요.", session.state.value.message)
    }

    @Test
    fun relaunchWithoutRestartingTheLauncherShowsOnlyTheNotice() {
        val session = openSession(notice = LAUNCHER_RESTART_DETAIL)

        assertEquals(SessionPhase.Finished, session.state.value.phase)
        assertTrue(session.state.value.notice)
        assertEquals(LAUNCHER_RESTART_DETAIL, session.state.value.message)
        assertEquals(0, session.state.value.exitCode)
        assertNoFailureReport(session.state.value)
    }

    @Test
    fun userExceptionFinishesWithItsMessageAndExitStatus() {
        var afterThrowCount = 0
        val failure = object : UserException("런처가 없어요") {
            override val exitStatus: Int = 17
            override fun afterThrow() {
                afterThrowCount += 1
            }
        }
        val directory = Files.createTempDirectory("installer-session-user")
        try {
            val logFile = oversizedLog(directory)
            assertNotEquals(applicationLogFile(), logFile)
            val session = openSession(applicationLog = logFile) { _ -> throw failure }

            session.accept()

            assertEquals(1, afterThrowCount)
            val state = session.state.value
            assertEquals(SessionPhase.Finished, state.phase)
            val message = state.message
            assertEquals("런처가 없어요", message)
            assertEquals(17, state.exitCode)
            assertFalse(state.declined)
            assertEquals("", state.logTail)
            assertNull(state.failureDetail)
            assertNoFailureReport(state)
            assertTrue(message != null)
            assertFalse(message.contains("TAIL_MARKER_ON_SCREEN"))
            assertFalse(message.contains("HEAD_MARKER_NOT_ON_SCREEN"))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun errorFinishesWithTheReportMessage() {
        assertUnexpectedFailure(Error("native"))
    }

    @Test
    fun genericExceptionFinishesWithTheReportMessage() {
        assertUnexpectedFailure(IllegalStateException("disk full"))
    }

    @Test
    fun missingOrEmptyLogStillShowsTheException() {
        val directory = Files.createTempDirectory("installer-session-empty-log")
        try {
            val failure = IllegalStateException("disk full")
            val missing = directory.resolve("missing.log")
            val empty = directory.resolve("empty.log")
            Files.writeString(empty, "")
            for (logFile in listOf(missing, empty)) {
                val session = openSession(applicationLog = logFile) { _ -> throw failure }
                session.accept()
                val state = session.state.value
                val logged = if (Files.isRegularFile(logFile)) Files.readString(logFile) else null
                assertEquals(SessionPhase.Finished, state.phase)
                assertEquals(InstallerSession.GENERIC_FAILURE_MESSAGE, state.message)
                assertEquals(InstallerSession.GENERIC_FAILURE_EXIT, state.exitCode)
                val detail = state.failureDetail
                assertEquals("", state.logTail)
                assertEquals(unexpectedFailureText(failure, logged), detail)
                assertTrue(detail != null)
                assertTrue(detail.contains("IllegalStateException"))
                assertTrue(detail.contains("disk full"))
                assertEquals(detail, failureClipboardText(state))
            }
            val blocked = directory.resolve("blocked.log")
            Files.writeString(blocked, "SECRET_LOG_BODY")
            if (supportsPosix(blocked)) {
                Files.setPosixFilePermissions(blocked, emptySet())
                try {
                    if (!Files.isReadable(blocked)) {
                        val session = openSession(applicationLog = blocked) { _ -> throw failure }
                        session.accept()
                        val state = session.state.value
                        val detail = state.failureDetail
                        assertEquals(unexpectedFailureText(failure, null), detail)
                        assertEquals("", state.logTail)
                        assertTrue(detail != null)
                        assertTrue(detail.contains("IllegalStateException"))
                        assertFalse(detail.contains("SECRET_LOG_BODY"))
                        assertEquals(InstallerSession.GENERIC_FAILURE_EXIT, state.exitCode)
                    }
                } finally {
                    Files.setPosixFilePermissions(
                        blocked,
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                }
            }
            assertNotEquals(applicationLogFile(), blocked)
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun failureReportMasksHomeAndUserName() {
        val home = "/Users/alice"
        val userName = "alice"
        val failure = IllegalStateException("failed at $home/mods")
        val directory = Files.createTempDirectory("installer-session-mask")
        try {
            val logFile = directory.resolve("application.log")
            val body = """
                user.home=$home
                user.name=$userName
                windows=C:\Users\alice\AppData
                kept=salice
                short=al
                TAIL_MARKER_ON_SCREEN
            """.trimIndent()
            Files.writeString(logFile, body)
            val session = openSession(
                applicationLog = logFile,
                logHome = home,
                logUserName = userName,
            ) { _ -> throw failure }
            session.accept()
            val detail = session.state.value.failureDetail
            val expected = unexpectedFailureText(
                failure,
                body,
                home = home,
                userName = userName,
            )
            assertEquals(expected, detail)
            assertEquals(detail, failureClipboardText(session.state.value))
            assertTrue(detail != null)
            assertTrue(detail.contains("IllegalStateException: failed at ~/mods"))
            assertTrue(detail.contains("user.home=~"))
            assertTrue(detail.contains("user.name=<user>"))
            assertTrue(detail.contains("windows=C:~\\AppData"))
            assertTrue(detail.contains("kept=salice"))
            assertTrue(detail.contains("short=al"))
            assertTrue(detail.contains("TAIL_MARKER_ON_SCREEN"))
            assertFalse(detail.contains(home))
            assertFalse(detail.contains("C:\\Users\\alice"))
            assertFalse(Regex("(?<![0-9A-Za-z_])alice(?![0-9A-Za-z_])").containsMatchIn(detail))
            val copied = mutableListOf<String>()
            copyFailureReport(session.state.value) { copied += it }
            assertEquals(listOf(detail), copied)
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun logTailKeepsOnlyTheEndOfALongText() {
        assertEquals("", logTail(null))
        assertEquals("", logTail(""))
        val head = "HEAD_MARKER_NOT_ON_SCREEN"
        val tail = "TAIL_MARKER_ON_SCREEN"
        val text = head + "y".repeat(APPLICATION_LOG_TAIL_CHARS) + tail
        val cut = logTail(text)
        assertEquals(APPLICATION_LOG_TAIL_CHARS, cut.length)
        assertTrue(cut.endsWith(tail))
        assertFalse(cut.contains(head))
        val failure = IllegalStateException("disk full")
        val shown = unexpectedFailureText(failure, text)
        assertEquals("IllegalStateException: disk full\n\n$cut", shown)
        assertEquals(unexpectedFailureText(failure, null), "IllegalStateException: disk full")
        assertEquals(unexpectedFailureText(IllegalStateException(), null), "IllegalStateException")
        assertEquals(shown, failureClipboardText(unexpectedState(shown, cut)))
    }

    @Test
    fun readApplicationLogTailReadsOnlyTheEnd() {
        val directory = Files.createTempDirectory("installer-log-tail")
        try {
            val file = oversizedLog(directory)
            val read = readApplicationLogTail(file)
            assertTrue(read != null)
            assertTrue(read.endsWith("TAIL_MARKER_ON_SCREEN\n"))
            assertFalse(read.contains("HEAD_MARKER_NOT_ON_SCREEN"))
            assertTrue(read.length <= APPLICATION_LOG_TAIL_CHARS * 4)
            assertNull(readApplicationLogTail(directory.resolve("missing.log")))
            val empty = directory.resolve("empty.log")
            Files.writeString(empty, "")
            assertEquals("", readApplicationLogTail(empty))
            assertNull(readApplicationLogTail(directory))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun applicationLogFileStaysUnderTheUserHome() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".robotmc-installer", "logs", "application.log"),
            applicationLogFile(),
        )
    }

    @Test
    fun finishedPhaseCopiesOnlyTheUnexpectedFailureReport() {
        val root = Path.of(System.getProperty("user.dir"))
        check(Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            "테스트 작업 디렉터리가 저장소 루트가 아닙니다: $root"
        }
        val window = Files.readString(
            root.resolve("src/main/kotlin/com/sysbot32/robotmc/installer/gui/InstallerWindow.kt"),
        )
        val finished = window.substringAfter("private fun ColumnScope.FinishedPhase")
            .substringBefore("private fun windowHeight")
        assertTrue(finished.contains("val report = failureClipboardText(state)"))
        val display = finished.substringAfter("if (report != null) {").substringBefore("} else {")
        assertTrue(display.contains("report"))
        assertFalse(display.contains("copyFailureReport"))
        assertFalse(display.contains("복사"))
        val elseBranch = finished.substringAfter("} else {").substringBefore("Row(")
        assertTrue(elseBranch.contains("Spacer"))
        assertFalse(elseBranch.contains("copyFailureReport"))
        assertFalse(elseBranch.contains("복사"))
        val buttonAt = finished.indexOf("if (report != null)", finished.indexOf("if (report != null)") + 1)
        assertTrue(buttonAt > 0)
        val button = finished.substring(buttonAt).substringBefore("Button(onClick = onClose)")
        val successBody = button.substringAfter("if (copyFailureReport(state)) {", "")
        assertTrue(successBody.substringBefore("}").contains("copyAttempt += 1"))
        val copiedBody = button.substringAfter("if (copied) {", "")
        assertTrue(copiedBody.substringBefore("}").contains("\"✓\""))
        assertFalse(button.substringBefore("if (copied) {").contains("\"✓\""))
        assertTrue(button.contains("\"복사\""))
        assertEquals(1, Regex("copyFailureReport\\(").findAll(finished).count())
        val session = Files.readString(
            root.resolve("src/main/kotlin/com/sysbot32/robotmc/installer/gui/InstallerSession.kt"),
        )
        val sources = window + session
        assertFalse(sources.contains("api.github.com"))
        assertFalse(sources.contains("ghp_"))
        assertFalse(sources.contains("github_pat_"))
        assertFalse(sources.contains("GITHUB_TOKEN"))
        assertFalse(sources.contains("createIssue"))
    }

    // 확률적 회귀 감지. 락으로 묶기 전에는 약 1e-5 확률로 work가 두 번 실행돼요.
    @Test
    fun acceptRacingSwitchModeNeverAllowsASecondRun() {
        repeat(100_000) {
            val runs = AtomicInteger()
            val session = openSession { _ -> runs.incrementAndGet() }
            val barrier = CyclicBarrier(2)
            val t = Thread {
                barrier.await()
                session.switchMode(Mode.UNINSTALL)
            }
            t.start()
            barrier.await()
            session.accept()
            t.join()
            if (session.state.value.phase == SessionPhase.Finished) {
                val before = runs.get()
                session.accept()
                assertEquals(before, runs.get())
            }
        }
    }

    private fun openSession(
        mode: Mode = Mode.INSTALL,
        totalSteps: Int = 4,
        notice: String? = null,
        applicationLog: Path = Path.of("build", "installer-session-unused-application.log"),
        logHome: String = "",
        logUserName: String = "",
        work: (ProgressService) -> Unit = { _ -> },
    ): InstallerSession {
        val progress = ProgressService()
        return InstallerSession(
            mode = mode,
            totalSteps = totalSteps,
            progress = progress,
            work = { work(progress) },
            notice = notice,
            applicationLog = applicationLog,
            logHome = logHome,
            logUserName = logUserName,
        )
    }

    private fun assertUnexpectedFailure(failure: Throwable) {
        val directory = Files.createTempDirectory("installer-session-failure")
        try {
            val logFile = oversizedLog(directory)
            assertNotEquals(applicationLogFile(), logFile)
            val full = Files.readString(logFile)
            val session = openSession(applicationLog = logFile) { _ -> throw failure }
            session.accept()
            val state = session.state.value
            val expected = unexpectedFailureText(failure, full)
            assertEquals(SessionPhase.Finished, state.phase)
            assertEquals("오류가 발생했어요.\n로그를 첨부해서 제보해 주세요.", state.message)
            assertEquals(InstallerSession.GENERIC_FAILURE_MESSAGE, state.message)
            assertNotEquals(0, state.exitCode)
            assertEquals(InstallerSession.GENERIC_FAILURE_EXIT, state.exitCode)
            val detail = state.failureDetail
            assertEquals(expected, detail)
            assertEquals(logTail(full), state.logTail)
            assertEquals(unexpectedFailureText(failure, readApplicationLogTail(logFile)), detail)
            assertTrue(detail != null)
            assertTrue(detail.startsWith("${failure.javaClass.simpleName}: ${failure.message}"))
            assertTrue(state.logTail.contains("TAIL_MARKER_ON_SCREEN"))
            assertFalse(state.logTail.contains("HEAD_MARKER_NOT_ON_SCREEN"))
            assertFalse(detail.contains("HEAD_MARKER_NOT_ON_SCREEN"))
            val copied = mutableListOf<String>()
            assertTrue(copyFailureReport(state) { copied += it })
            assertEquals(listOf(expected), copied)
            assertEquals(expected, failureClipboardText(state))
            assertFalse(copyFailureReport(state) { throw IllegalStateException("clipboard busy") })
            assertFailsWith<CancellationException> {
                copyFailureReport(state) { throw CancellationException("stopped") }
            }
        } finally {
            deleteTree(directory)
        }
    }

    private fun assertNoFailureReport(state: SessionState) {
        assertEquals("", state.logTail)
        assertNull(state.failureDetail)
        assertNull(failureClipboardText(state))
        val copied = mutableListOf<String>()
        assertFalse(copyFailureReport(state) { copied += it })
        assertTrue(copied.isEmpty())
    }

    private fun unexpectedState(detail: String, tail: String): SessionState = SessionState(
        phase = SessionPhase.Finished,
        mode = Mode.INSTALL,
        prompt = "",
        status = "",
        statuses = emptyList(),
        completedSteps = 0,
        totalSteps = 1,
        message = InstallerSession.GENERIC_FAILURE_MESSAGE,
        exitCode = InstallerSession.GENERIC_FAILURE_EXIT,
        declined = false,
        failureDetail = detail,
        logTail = tail,
    )

    private fun oversizedLog(directory: Path): Path {
        val file = directory.resolve("application.log")
        val head = "HEAD_MARKER_NOT_ON_SCREEN\n"
        val tail = "\nTAIL_MARKER_ON_SCREEN\n"
        Files.writeString(file, head + "x".repeat(APPLICATION_LOG_TAIL_CHARS * 4) + tail)
        return file
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) {
            return
        }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun supportsPosix(path: Path): Boolean =
        path.fileSystem.supportedFileAttributeViews().contains("posix")
}
