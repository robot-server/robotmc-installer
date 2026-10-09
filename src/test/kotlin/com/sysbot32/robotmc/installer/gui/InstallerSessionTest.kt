package com.sysbot32.robotmc.installer.gui

import com.sysbot32.robotmc.installer.config.InstallerProperties.Mode
import com.sysbot32.robotmc.installer.prelaunch.LAUNCHER_RESTART_DETAIL
import com.sysbot32.robotmc.installer.exception.UserException
import com.sysbot32.robotmc.installer.progress.ProgressService
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val session = openSession { _ -> throw failure }

        session.accept()

        assertEquals(1, afterThrowCount)
        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertEquals("런처가 없어요", state.message)
        assertEquals(17, state.exitCode)
        assertFalse(state.declined)
    }

    @Test
    fun errorFinishesWithTheReportMessage() {
        val session = openSession { _ -> throw Error("native") }

        session.accept()

        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertEquals("오류가 발생했어요.\n로그 파일을 첨부해서 제보해 주세요.", state.message)
        assertEquals(InstallerSession.GENERIC_FAILURE_EXIT, state.exitCode)
    }

    @Test
    fun genericExceptionFinishesWithTheReportMessage() {
        val session = openSession { _ -> throw IllegalStateException("disk full") }

        session.accept()

        val state = session.state.value
        assertEquals(SessionPhase.Finished, state.phase)
        assertEquals("오류가 발생했어요.\n로그 파일을 첨부해서 제보해 주세요.", state.message)
        assertNotEquals(0, state.exitCode)
        assertEquals(InstallerSession.GENERIC_FAILURE_EXIT, state.exitCode)
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
        work: (ProgressService) -> Unit = { _ -> },
    ): InstallerSession {
        val progress = ProgressService()
        return InstallerSession(
            mode = mode,
            totalSteps = totalSteps,
            progress = progress,
            work = { work(progress) },
            notice = notice,
        )
    }
}
