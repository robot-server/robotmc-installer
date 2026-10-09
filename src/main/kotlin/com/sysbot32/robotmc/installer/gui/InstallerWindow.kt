package com.sysbot32.robotmc.installer.gui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.sysbot32.robotmc.installer.config.InstallerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.Window as AwtWindow

private val log = KotlinLogging.logger { }

private val InstallerColors = darkColorScheme(
    primary = Color(0xFF8BD17C),
    onPrimary = Color(0xFF10210E),
    background = Color(0xFF221C16),
    surface = Color(0xFF221C16),
    onSurface = Color(0xFFF3EEE6),
    onSurfaceVariant = Color(0xFFC4B8A8),
    surfaceVariant = Color(0xFF3A3128),
    secondaryContainer = Color(0xFF3A3128),
    outline = Color(0xFF6E6256),
)

private enum class InfoPanel {
    About,
    Settings,
}

@Composable
fun InstallerWindow(
    session: InstallerSession,
    properties: InstallerProperties,
    onExit: (Int) -> Unit,
) {
    val state by session.state.collectAsState()
    var panel by remember { mutableStateOf<InfoPanel?>(null) }
    var applicationMenu by remember { mutableStateOf(false) }
    var menuReady by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    val updateNotice = installerUpdateNotice(properties)
    var showUpdateAlert by remember { mutableStateOf(updateNotice != null) }
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        width = 520.dp,
        height = if (updateNotice == null) 420.dp else 500.dp,
    )
    val detailsOpen = showPlan && state.phase == SessionPhase.Confirm
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
        state = windowState,
    ) {
        LaunchedEffect(detailsOpen, updateNotice != null) {
            val height = windowHeight(detailsOpen, updateNotice != null, window)
            windowState.size = DpSize(520.dp, height)
            moveInsideScreen(windowState, window)
        }
        LaunchedEffect(Unit) {
            applicationMenu = registerApplicationMenu(
                onAbout = { panel = InfoPanel.About },
                onSettings = { panel = InfoPanel.Settings },
            )
            menuReady = true
            if (applicationMenu) {
                log.info { "앱 메뉴 등록: 정보, 설정" }
            }
        }
        MenuBar {
            if (menuReady && !applicationMenu) {
                Menu(APPLICATION_NAME) {
                    Item("정보", onClick = { panel = InfoPanel.About })
                    Item(
                        "설정",
                        onClick = { panel = InfoPanel.Settings },
                        shortcut = KeyShortcut(Key.Comma, meta = true),
                    )
                }
            }
            Menu("동작") {
                RadioButtonItem(
                    "설치",
                    selected = state.mode == InstallerProperties.Mode.INSTALL,
                    enabled = state.phase != SessionPhase.Working,
                    onClick = { session.switchMode(InstallerProperties.Mode.INSTALL) },
                )
                RadioButtonItem(
                    "제거",
                    selected = state.mode == InstallerProperties.Mode.UNINSTALL,
                    enabled = state.phase != SessionPhase.Working,
                    onClick = { session.switchMode(InstallerProperties.Mode.UNINSTALL) },
                )
            }
        }
        InstallerScreen(
            session = session,
            properties = properties,
            state = state,
            showPlan = showPlan,
            updateNotice = updateNotice,
            onTogglePlan = { showPlan = !showPlan },
            onExit = onExit,
        )
    }
    when (panel) {
        InfoPanel.About -> AboutDialog(properties, onClose = { panel = null })
        InfoPanel.Settings -> SettingsDialog(properties, state.mode, onClose = { panel = null })
        null -> Unit
    }
    if (showUpdateAlert && updateNotice != null) {
        UpdateAlertDialog(updateNotice, onClose = { showUpdateAlert = false })
    }
}

@Composable
private fun UpdateAlertDialog(notice: String, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "설치기 업데이트",
        state = rememberDialogState(position = WindowPosition(Alignment.Center), width = 440.dp, height = 320.dp),
        resizable = false,
        alwaysOnTop = true,
    ) {
        InstallerSurface {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("설치기 업데이트", style = MaterialTheme.typography.headlineMedium)
                Text(notice, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("확인") }
            }
        }
    }
}

@Composable
private fun AboutDialog(properties: InstallerProperties, onClose: () -> Unit) {
    val notice = installerUpdateNotice(properties)
    DialogWindow(
        onCloseRequest = onClose,
        title = "정보",
        state = rememberDialogState(width = 420.dp, height = if (notice == null) 280.dp else 360.dp),
    ) {
        InstallerSurface {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    APPLICATION_NAME,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "서버 접속에 필요한 모드 로더와 모드를 설치하거나 제거해요.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                applicationVersion()?.let { version ->
                    Text(
                        "버전 $version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                notice?.let { text ->
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    properties: InstallerProperties,
    mode: InstallerProperties.Mode,
    onClose: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        title = "설정",
        state = rememberDialogState(width = 520.dp, height = 460.dp),
    ) {
        InstallerSurface {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("설정", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "이 실행에 적용된 구성이에요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    properties.settingsRows(mode).forEach { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                row.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(row.value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun InstallerSurface(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InstallerColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun InstallerScreen(
    session: InstallerSession,
    properties: InstallerProperties,
    state: SessionState,
    showPlan: Boolean,
    updateNotice: String?,
    onTogglePlan: () -> Unit,
    onExit: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    InstallerSurface {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                APPLICATION_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            when (state.phase) {
                SessionPhase.Confirm -> ConfirmPhase(
                    state = state,
                    properties = properties,
                    showPlan = showPlan,
                    updateNotice = updateNotice,
                    onTogglePlan = onTogglePlan,
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

@Composable
private fun ColumnScope.ConfirmPhase(
    state: SessionState,
    properties: InstallerProperties,
    showPlan: Boolean,
    updateNotice: String?,
    onTogglePlan: () -> Unit,
    modifier: Modifier,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(InstallerSession.actionLabel(state.mode), style = MaterialTheme.typography.headlineMedium)
        Text(state.prompt, style = MaterialTheme.typography.bodyLarge)
        updateNotice?.let { notice ->
            Text(notice, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Minecraft ${properties.minecraft.version}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onTogglePlan,
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(if (showPlan) "접기" else "자세히 보기")
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showPlan) {
                properties.plan(state.mode).forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        section.items.forEach { item ->
                            Text(item, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
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

private fun windowHeight(detailsOpen: Boolean, updateNotice: Boolean, window: AwtWindow): Dp {
    val config = window.graphicsConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val usable = (config.bounds.height - insets.top - insets.bottom).coerceAtLeast(1)
    val desired = when {
        detailsOpen -> 720
        updateNotice -> 500
        else -> 420
    }
    return minOf(desired, usable).dp
}

/**
 * 처음 위치는 [WindowPosition]의 가운데 정렬이 정한다.
 * 자세히 보기로 커진 뒤에 화면 밖으로 나가면, 가운데로 되돌리지 않고 들어가는 만큼만 옮긴다.
 */
private fun moveInsideScreen(windowState: WindowState, window: AwtWindow) {
    val position = windowState.position
    if (!position.isSpecified) {
        return
    }
    val config = window.graphicsConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val bounds = config.bounds
    val usableLeft = bounds.x + insets.left
    val usableTop = bounds.y + insets.top
    val usableRight = bounds.x + bounds.width - insets.right
    val usableBottom = bounds.y + bounds.height - insets.bottom
    val width = windowState.size.width.value
    val height = windowState.size.height.value
    val maxX = usableRight - width
    val maxY = usableBottom - height
    val x = if (maxX < usableLeft) {
        usableLeft.toFloat()
    } else {
        position.x.value.coerceIn(usableLeft.toFloat(), maxX.toFloat())
    }
    val y = if (maxY < usableTop) {
        usableTop.toFloat()
    } else {
        position.y.value.coerceIn(usableTop.toFloat(), maxY.toFloat())
    }
    if (x != position.x.value || y != position.y.value) {
        windowState.position = WindowPosition(x.dp, y.dp)
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
