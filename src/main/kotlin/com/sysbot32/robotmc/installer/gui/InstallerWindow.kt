package com.sysbot32.robotmc.installer.gui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val log = KotlinLogging.logger { }

private val InstallerColors = darkColorScheme(
    primary = Color(0xFF8BD17C),
    onPrimary = Color(0xFF10210E),
    background = Color(0xFF121417),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFB4B8BE),
)

@Composable
fun InstallerWindow(
    session: InstallerSession,
    onExit: (Int) -> Unit,
) {
    val state by session.state.collectAsState()
    Window(
        onCloseRequest = {
            when (session.state.value.phase) {
                SessionPhase.Confirm -> {
                    session.decline()
                    onExit(session.state.value.exitCode ?: 0)
                }
                SessionPhase.Working -> Unit
                SessionPhase.Finished -> onExit(session.state.value.exitCode ?: InstallerSession.GENERIC_FAILURE_EXIT)
            }
        },
        title = windowTitle(state),
        state = rememberWindowState(width = 520.dp, height = 420.dp),
    ) {
        InstallerScreen(session, state, onExit)
    }
}

@Composable
private fun InstallerScreen(
    session: InstallerSession,
    state: SessionState,
    onExit: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        log.info { "화면 표시: ${state.phase}" }
    }
    MaterialTheme(colorScheme = InstallerColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "RobotMC",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                when (state.phase) {
                    SessionPhase.Confirm -> ConfirmPhase(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onDecline = {
                            session.decline()
                            onExit(session.state.value.exitCode ?: 0)
                        },
                        onAccept = {
                            scope.launch(Dispatchers.IO) { session.accept() }
                        },
                    )
                    SessionPhase.Working -> WorkingPhase(state, Modifier.weight(1f))
                    SessionPhase.Finished -> FinishedPhase(
                        state = state,
                        modifier = Modifier.weight(1f),
                        onClose = { onExit(state.exitCode ?: InstallerSession.GENERIC_FAILURE_EXIT) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConfirmPhase(
    state: SessionState,
    modifier: Modifier,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(InstallerSession.actionLabel(state.mode), style = MaterialTheme.typography.headlineMedium)
        Text(state.prompt, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onDecline) { Text("취소") }
            Button(onClick = onAccept) { Text(InstallerSession.actionLabel(state.mode)) }
        }
    }
}

@Composable
private fun ColumnScope.WorkingPhase(state: SessionState, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(InstallerSession.workingTitle(state.mode), style = MaterialTheme.typography.headlineMedium)
        Text(
            state.status.ifBlank { "준비 중..." },
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${state.completedSteps.coerceAtLeast(0)} / ${state.totalSteps}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.statuses.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FinishedPhase(
    state: SessionState,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val failed = (state.exitCode ?: 0) != 0
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            when {
                state.declined -> "취소"
                failed -> "오류"
                else -> "완료"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
        ) { Text("닫기") }
    }
}

private fun windowTitle(state: SessionState): String = when (state.phase) {
    SessionPhase.Confirm -> InstallerSession.actionLabel(state.mode)
    SessionPhase.Working -> InstallerSession.workingTitle(state.mode)
    SessionPhase.Finished -> when {
        state.declined -> "취소"
        (state.exitCode ?: 0) != 0 -> "오류"
        else -> "완료"
    }
}
