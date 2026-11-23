package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autodora.next.BuildConfig
import com.autodora.next.R
import com.autodora.next.data.*
import com.autodora.next.model.*
import com.autodora.next.touch.SwipeSpeedPreset
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class PermissionHealthState(
    val overlayGranted: Boolean,
    val accessibilityConnected: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val overlayRunning: Boolean
)

private enum class HomeTab(val labelRes: Int) {
    OVERVIEW(R.string.tab_overview),
    STRATEGIES(R.string.tab_strategies),
    SETTINGS(R.string.tab_settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    health: PermissionHealthState,
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onOpenGitHub: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (UpdateCheckState.UpdateAvailable) -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onInstallReadyApk: (String) -> Unit = {},
    onOpenUpdateUrl: (String) -> Unit = {},
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onSaveAsNewPreset: (String, String, RuleConfig) -> Unit,
    onDuplicatePreset: (TeamBasePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit
) {
    val ctx = LocalContext.current
    var activeTab by remember { mutableStateOf(HomeTab.OVERVIEW) }
    var helpPopup by remember { mutableStateOf<Pair<String, String>?>(null) }

    val activePresetRecord = presets.firstOrNull { it.preset.id == settings.activePresetId }
        ?: presets.firstOrNull()
    val activePresetName = if (activePresetRecord != null) {
        LocaleHelper.displayPresetName(ctx, activePresetRecord.preset, activePresetRecord.isBuiltIn)
    } else {
        stringResource(R.string.preset_general_name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = onToggleOverlayService,
                        label = {
                            Text(
                                text = stringResource(
                                    if (health.overlayRunning) R.string.status_running else R.string.status_stopped
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (health.overlayRunning) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            labelColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            leadingIconContentColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        border = null,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    val label = stringResource(tab.labelRes)
                    val icon = when (tab) {
                        HomeTab.OVERVIEW -> Icons.Outlined.Dashboard
                        HomeTab.STRATEGIES -> Icons.Outlined.Tune
                        HomeTab.SETTINGS -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { activeTab = tab },
                        icon = {
                            if (tab == HomeTab.STRATEGIES && activeWaveChips.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${activeWaveChips.size}") } }) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                HomeTab.OVERVIEW -> OverviewTab(
                    health = health,
                    settings = settings,
                    activePresetName = activePresetName,
                    activeWaveChips = activeWaveChips,
                    onRequestOverlay = onRequestOverlay,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenAppDetails = onOpenAppDetails,
                    onRequestMediaProjection = onRequestMediaProjection,
                    onRequestNotifications = onRequestNotifications,
                    onToggleOverlayService = onToggleOverlayService,
                    onNavigateTab = { activeTab = it }
                )

                HomeTab.STRATEGIES -> UnifiedStrategyTab(
                    settings = settings,
                    presets = presets,
                    activeWaveChips = activeWaveChips,
                    onUpdateSettings = onUpdateSettings,
                    onSelectPreset = onSelectPreset,
                    onSavePreset = onSavePreset,
                    onSaveAsNewPreset = onSaveAsNewPreset,
                    onDuplicatePreset = onDuplicatePreset,
                    onDeletePreset = onDeletePreset,
                    onToggleWaveChip = onToggleWaveChip,
                    onClearWaveChips = onClearWaveChips,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.SETTINGS -> SettingsTab(
                    settings = settings,
                    benchmarkReport = benchmarkReport,
                    isBenchmarking = isBenchmarking,
                    updateCheckState = updateCheckState,
                    onUpdateSettings = onUpdateSettings,
                    onRunNativeBenchmark = onRunNativeBenchmark,
                    onOpenGitHub = onOpenGitHub,
                    onCheckForUpdates = onCheckForUpdates,
                    onDownloadUpdate = onDownloadUpdate,
                    onCancelUpdateDownload = onCancelUpdateDownload,
                    onInstallReadyApk = onInstallReadyApk,
                    onOpenUpdateUrl = onOpenUpdateUrl,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )
            }
        }
    }

    helpPopup?.let { (hTitle, hBody) ->
        LocalizedHelpDialog(
            title = hTitle,
            body = hBody,
            dismissLabel = ctx.getString(R.string.btn_got_it),
            onDismiss = { helpPopup = null }
        )
    }
}

// ==================== Tab 0: Overview Hub (Colored Collapsible Drawers, Default All Expanded) ====================
@Composable
private fun OverviewTab(
    health: PermissionHealthState,
    settings: AppSettings,
    activePresetName: String,
    activeWaveChips: List<WaveShieldChip>,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onNavigateTab: (HomeTab) -> Unit
) {
    val ctx = LocalContext.current
    val speedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
    val totalPerms = if (health.notificationsGranted) 3 else 4
    val readyPerms = listOf(
        health.overlayGranted,
        health.accessibilityConnected,
        health.mediaProjectionReady
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Indigo Drawer: Current Strategy & Overlay Control
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.overview_current_strategy),
            accentColor = DrawerAccentColors.Indigo,
            summaryText = activePresetName,
            highlighted = health.overlayRunning,
            initialExpanded = true,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activePresetName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { onNavigateTab(HomeTab.STRATEGIES) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.overview_switch_strategy),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoPill(
                    label = stringResource(R.string.overview_pill_steps, settings.maxSteps),
                    onClick = { onNavigateTab(HomeTab.SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = stringResource(R.string.overview_pill_speed, LocaleHelper.speedLabel(ctx, speedPreset)),
                    onClick = { onNavigateTab(HomeTab.SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = stringResource(R.string.overview_pill_shields, activeWaveChips.size),
                    onClick = { onNavigateTab(HomeTab.STRATEGIES) },
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = onToggleOverlayService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = if (health.overlayRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            ) {
                Icon(
                    imageVector = if (health.overlayRunning) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (health.overlayRunning) R.string.btn_stop_overlay else R.string.btn_start_overlay
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 2. Teal Drawer: Permissions Health
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_permissions),
            accentColor = DrawerAccentColors.Teal,
            summaryText = "$readyPerms/$totalPerms",
            highlighted = readyPerms == totalPerms,
            initialExpanded = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PermissionItemRow(
                    title = stringResource(R.string.perm_overlay_title),
                    subtitle = stringResource(R.string.perm_overlay_sub),
                    granted = health.overlayGranted,
                    actionLabel = stringResource(R.string.perm_grant),
                    allowAlwaysClick = false,
                    onAction = onRequestOverlay
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionItemRow(
                    title = stringResource(R.string.perm_a11y_title),
                    subtitle = stringResource(R.string.perm_a11y_sub),
                    granted = health.accessibilityConnected,
                    actionLabel = stringResource(R.string.perm_enable),
                    allowAlwaysClick = true,
                    onAction = onOpenAccessibility
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionItemRow(
                    title = stringResource(R.string.perm_capture_title),
                    subtitle = stringResource(R.string.perm_capture_sub),
                    granted = health.mediaProjectionReady,
                    actionLabel = stringResource(R.string.perm_grant),
                    allowAlwaysClick = false,
                    onAction = onRequestMediaProjection
                )
                if (!health.notificationsGranted) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionItemRow(
                        title = stringResource(R.string.perm_notif_title),
                        subtitle = stringResource(R.string.perm_notif_sub),
                        granted = false,
                        actionLabel = stringResource(R.string.perm_grant),
                        allowAlwaysClick = false,
                        onAction = onRequestNotifications
                    )
                }
            }
        }

        // 3. Amber Drawer: Floating Capsule Usage Guide
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.guide_title),
            accentColor = DrawerAccentColors.Amber,
            initialExpanded = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.guide_line_1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.guide_line_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.guide_line_3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 4. Crimson Drawer: HyperOS / Android 13+ Restricted Settings Guide
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.hyperos_title),
            accentColor = DrawerAccentColors.Crimson,
            initialExpanded = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.hyperos_guide_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onOpenAppDetails,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.hyperos_app_details),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}


@Composable
internal fun ElementColorTogglePill(
    elemIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = ""
) {
    val ctx = LocalContext.current
    val badgeColor = Color(LocaleHelper.elementBadgeArgb(elemIndex))
    val label = LocaleHelper.elementLabel(ctx, elemIndex) + suffix
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) badgeColor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) badgeColor else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.height(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
internal fun NumericStepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onMinus,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) { Text("−") }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            FilledTonalButton(
                onClick = onPlus,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) { Text("+") }
        }
    }
}

// ==================== Tab 3: Settings, Theme, Language, Slim Sliders & Countdown Dropdown ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    settings: AppSettings,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    updateCheckState: UpdateCheckState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onOpenGitHub: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (UpdateCheckState.UpdateAvailable) -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onInstallReadyApk: (String) -> Unit,
    onOpenUpdateUrl: (String) -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    val context = LocalContext.current
    val overlayPalette = remember(settings.themeMode) { OverlayThemeResolver.resolve(context, settings.themeMode) }

    var maxStepsInput by remember { mutableStateOf(settings.maxSteps.toString()) }
    LaunchedEffect(settings.maxSteps) {
        if (maxStepsInput.toIntOrNull() != settings.maxSteps) {
            maxStepsInput = settings.maxSteps.toString()
        }
    }
    val parsedSteps = maxStepsInput.toIntOrNull()
    val maxStepsError = maxStepsInput.isNotEmpty() && (parsedSteps == null || parsedSteps !in 8..300)
    var countdownMenuExpanded by remember { mutableStateOf(false) }

    val msUnit = stringResource(R.string.unit_ms)
    val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
    val invertActive = settings.invertDragX || settings.invertDragY
    val enabledModesCount = listOf(
        settings.testModeOnlyDraw,
        settings.holdReplanEnabled,
        invertActive,
        settings.allowDiagonalMove,
        settings.calibration.autoDetectWeijian,
        settings.showOrbBadgesOnPath,
        settings.humanMode,
        settings.showEmergencyStop
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Purple Drawer: Appearance, Theme Mode & Display Language (matches Floating Settings Purple)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_appearance),
            accentColor = DrawerAccentColors.Purple,
            summaryText = "${LocaleHelper.themeLabel(context, settings.themeMode)} · ${LocaleHelper.languageLabel(context, settings.language)} · ${stringResource(if (settings.floatingBarVertical) R.string.floating_bar_vertical else R.string.floating_bar_horizontal)}${stringResource(if (settings.floatingBarDockRight) R.string.floating_bar_dock_right else R.string.floating_bar_dock_left)}",
            highlighted = true,
            initialExpanded = true
        ) {
            Text(
                text = stringResource(R.string.theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
                        icon = {
                            Icon(
                                imageVector = when (mode) {
                                    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                    ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                    ThemeMode.DARK -> Icons.Outlined.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(
                            LocaleHelper.themeLabel(context, mode),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = stringResource(R.string.language_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { idx, lang ->
                    SegmentedButton(
                        selected = settings.language == lang,
                        onClick = { onUpdateSettings { it.copy(language = lang) } },
                        shape = SegmentedButtonDefaults.itemShape(idx, AppLanguage.entries.size)
                    ) {
                        Text(
                            LocaleHelper.languageLabel(context, lang),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = stringResource(R.string.floating_bar_layout_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val barLayouts = listOf(
                    Triple(false, R.string.floating_bar_horizontal, Icons.Outlined.MoreHoriz),
                    Triple(true, R.string.floating_bar_vertical, Icons.Outlined.MoreVert)
                )
                barLayouts.forEachIndexed { idx, (isVertical, labelRes, iconVec) ->
                    SegmentedButton(
                        selected = settings.floatingBarVertical == isVertical,
                        onClick = { onUpdateSettings { it.copy(floatingBarVertical = isVertical) } },
                        shape = SegmentedButtonDefaults.itemShape(idx, barLayouts.size),
                        icon = {
                            Icon(
                                imageVector = iconVec,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.floating_bar_dock_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val dockSides = listOf(
                    Triple(false, R.string.floating_bar_dock_left, Icons.AutoMirrored.Outlined.FormatAlignLeft),
                    Triple(true, R.string.floating_bar_dock_right, Icons.AutoMirrored.Outlined.FormatAlignRight)
                )
                dockSides.forEachIndexed { idx, (isRight, labelRes, iconVec) ->
                    SegmentedButton(
                        selected = settings.floatingBarDockRight == isRight,
                        onClick = {
                            onUpdateSettings {
                                it.copy(floatingBarDockRight = isRight, floatingBarPosXRatio = -1f)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(idx, dockSides.size),
                        icon = {
                            Icon(
                                imageVector = iconVec,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    ) {
                        Text(
                            stringResource(labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.floating_bar_memory_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 2. Amber Drawer: Swipe Speed Presets + Slim Native Slider (matches Floating Quick Amber)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_speed),
            accentColor = DrawerAccentColors.Amber,
            summaryText = "${LocaleHelper.speedLabel(context, activeSpeed)} · ${settings.stepDurationMs} $msUnit",
            highlighted = true,
            initialExpanded = true
        ) {
            Text(
                text = stringResource(R.string.speed_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (rowPresets in SwipeSpeedPreset.entries.chunked(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (preset in rowPresets) {
                            val selected = activeSpeed == preset
                            val style = overlayPalette.speedStyle(preset)
                            Surface(
                                onClick = { onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) } },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) Color(style.bg) else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) Color(style.stroke) else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = LocaleHelper.speedLabel(context, preset),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${preset.stepDurationMs} $msUnit",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SlimNativeSlider(
                value = settings.stepDurationMs.toFloat().coerceIn(20f, 120f),
                onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                valueRange = 20f..120f
            )
        }

        // 3. Blue Drawer: Solver Budget, Slim Native Sliders & Countdown Dropdown (matches Floating Settings Blue)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_engine_steps),
            accentColor = DrawerAccentColors.Blue,
            summaryText = "${settings.maxSteps}${stringResource(R.string.unit_steps)} · ${settings.beamWidth}",
            highlighted = true,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_engine_settings_title),
                    context.getString(R.string.help_engine_settings_body)
                )
            }
        ) {
            Text(
                text = stringResource(R.string.priority_tier_hint_settings),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = maxStepsInput,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    maxStepsInput = digits
                    val v = digits.toIntOrNull()
                    if (v != null && v in 8..300) {
                        onUpdateSettings { it.copy(maxSteps = v) }
                    }
                },
                singleLine = true,
                isError = maxStepsError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.field_max_steps)) },
                suffix = { Text(stringResource(R.string.unit_steps)) },
                supportingText = {
                    Text(
                        if (maxStepsError) stringResource(R.string.error_max_steps)
                        else stringResource(R.string.support_max_steps, settings.maxSteps)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (stepVal in listOf(30, 60, 100, 150, 200)) {
                    CompactChoicePill(
                        label = "$stepVal",
                        selected = settings.maxSteps == stepVal,
                        onClick = {
                            maxStepsInput = stepVal.toString()
                            onUpdateSettings { it.copy(maxSteps = stepVal) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SliderSettingRow(
                label = stringResource(R.string.slider_beam),
                valueText = "${settings.beamWidth}",
                value = settings.beamWidth.toFloat().coerceIn(40f, 600f),
                valueRange = 40f..600f,
                onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = ((v / 20f).roundToInt() * 20).coerceIn(40, 600)) } }
            )
            SliderSettingRow(
                label = stringResource(R.string.slider_hold_delay),
                valueText = "${settings.holdReplanDelayMs} $msUnit",
                value = settings.holdReplanDelayMs.toFloat().coerceIn(240f, 900f),
                valueRange = 240f..900f,
                onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = ((v / 20f).roundToLong() * 20L).coerceIn(240L, 900L)) } }
            )
            Text(
                text = stringResource(R.string.slider_hold_delay_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val solveSecFormatted = String.format(java.util.Locale.US, "%.2fs", settings.holdReplanSolveTimeMs / 1000.0)
            SliderSettingRow(
                label = stringResource(R.string.slider_hold_solve_time),
                valueText = "$solveSecFormatted (${settings.holdReplanSolveTimeMs} $msUnit)",
                value = settings.holdReplanSolveTimeMs.toFloat().coerceIn(150f, 3500f),
                valueRange = 150f..3500f,
                onValueChange = { v ->
                    onUpdateSettings {
                        it.copy(holdReplanSolveTimeMs = ((v / 50f).roundToLong() * 50L).coerceIn(150L, 3500L))
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((presetLabel, presetMs) in listOf(
                    "0.3s" to 300L,
                    "0.6s" to 600L,
                    "1.2s" to 1200L,
                    "2.0s" to 2000L,
                    "3.5s" to 3500L
                )) {
                    CompactChoicePill(
                        label = presetLabel,
                        selected = settings.holdReplanSolveTimeMs == presetMs,
                        onClick = { onUpdateSettings { it.copy(holdReplanSolveTimeMs = presetMs) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                text = stringResource(R.string.slider_hold_solve_time_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Pre-Swipe Countdown Native Exposed Dropdown (pre-resolved with localized context so Popup never falls back to system locale!)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val countdownItems = remember(settings.language, context) {
                listOf(0, 1, 2, 3, 5).map { sec ->
                    sec to if (sec == 0) {
                        context.getString(R.string.countdown_none)
                    } else {
                        context.getString(R.string.unit_seconds, sec)
                    }
                }
            }
            val currentCountdownText = countdownItems.firstOrNull { it.first == settings.countdownSeconds }?.second
                ?: context.getString(R.string.unit_seconds, settings.countdownSeconds)
            ExposedDropdownMenuBox(
                expanded = countdownMenuExpanded,
                onExpandedChange = { countdownMenuExpanded = !countdownMenuExpanded }
            ) {
                OutlinedTextField(
                    value = currentCountdownText,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(context.getString(R.string.slider_countdown)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = countdownMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = countdownMenuExpanded,
                    onDismissRequest = { countdownMenuExpanded = false }
                ) {
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalConfiguration provides context.resources.configuration
                    ) {
                        countdownItems.forEach { (sec, itemText) ->
                            DropdownMenuItem(
                                text = { Text(itemText) },
                                onClick = {
                                    onUpdateSettings { it.copy(countdownSeconds = sec) }
                                    countdownMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // 4. Teal Drawer: Mode & Control Switches (matches Floating Quick & Settings Teal)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_modes),
            accentColor = DrawerAccentColors.Teal,
            summaryText = "$enabledModesCount/8",
            highlighted = enabledModesCount > 0,
            initialExpanded = true,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchSettingRow(
                    title = stringResource(R.string.mode_test_title),
                    subtitle = stringResource(R.string.mode_test_sub),
                    checked = settings.testModeOnlyDraw,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_hold_title),
                    subtitle = stringResource(R.string.mode_hold_sub),
                    checked = settings.holdReplanEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanEnabled = v) } }
                )
                // Nested sub-option under Hold-and-Replan: trigger style (Hold in Place vs Nudge 1 Cell)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (settings.holdReplanEnabled) {
                            DrawerAccentColors.Teal.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, bottom = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mode_hold_trigger_label),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.holdReplanEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChoicePill(
                                label = stringResource(R.string.mode_hold_trigger_inplace),
                                selected = !settings.holdReplanNudge,
                                onClick = { onUpdateSettings { it.copy(holdReplanNudge = false) } },
                                modifier = Modifier.weight(1f)
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.mode_hold_trigger_nudge),
                                selected = settings.holdReplanNudge,
                                onClick = { onUpdateSettings { it.copy(holdReplanNudge = true) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = stringResource(R.string.mode_hold_trigger_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 4-State Invert Drag Direction Selector (matches Floating Quick & Settings 1:1)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mode_invert_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.mode_invert_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val invertOptions = listOf(
                            Triple(R.string.invert_off, false, false),
                            Triple(R.string.invert_x, true, false),
                            Triple(R.string.invert_y, false, true),
                            Triple(R.string.invert_xy, true, true)
                        )
                        for ((lblRes, ix, iy) in invertOptions) {
                            CompactChoicePill(
                                label = stringResource(lblRes),
                                selected = settings.invertDragX == ix && settings.invertDragY == iy,
                                onClick = { onUpdateSettings { it.copy(invertDragX = ix, invertDragY = iy) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_diag_title),
                    subtitle = stringResource(R.string.mode_diag_sub),
                    checked = settings.allowDiagonalMove,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_weijian_title),
                    subtitle = stringResource(R.string.mode_weijian_sub),
                    checked = settings.calibration.autoDetectWeijian,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_badges_title),
                    subtitle = stringResource(R.string.mode_badges_sub),
                    checked = settings.showOrbBadgesOnPath,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showOrbBadgesOnPath = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.mode_question_mem_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.mode_question_mem_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_human_title),
                    subtitle = stringResource(R.string.mode_human_sub),
                    checked = settings.humanMode,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_stop_title),
                    subtitle = stringResource(R.string.mode_stop_sub),
                    checked = settings.showEmergencyStop,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                )
            }
        }

        // 5. Indigo Drawer: Local Engine Benchmark
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_benchmark),
            accentColor = DrawerAccentColors.Indigo,
            initialExpanded = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.bench_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.bench_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onRunNativeBenchmark,
                    enabled = !isBenchmarking
                ) {
                    Text(
                        stringResource(if (isBenchmarking) R.string.btn_bench_running else R.string.btn_bench_run)
                    )
                }
            }
            if (benchmarkReport != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = benchmarkReport,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    )
                }
            }
        }

        // 6. Crimson Drawer: About & Version (with GitHub link & Check for Updates)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_about),
            accentColor = DrawerAccentColors.Crimson,
            summaryText = "v${BuildConfig.VERSION_NAME}",
            highlighted = true,
            initialExpanded = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AutoDora-NEXT",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.about_version_fmt,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenGitHub,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.about_github_btn),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val updateBusy = updateCheckState is UpdateCheckState.Checking || updateCheckState is UpdateCheckState.Downloading
                FilledTonalButton(
                    onClick = onCheckForUpdates,
                    enabled = !updateBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (updateCheckState is UpdateCheckState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (updateCheckState is UpdateCheckState.Checking) {
                                R.string.about_checking_update
                            } else {
                                R.string.about_check_update_btn
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (val st = updateCheckState) {
                UpdateCheckState.Idle -> Unit
                UpdateCheckState.Checking -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.about_checking_update),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                is UpdateCheckState.UpToDate -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.about_up_to_date_fmt, st.currentVersion),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                is UpdateCheckState.UpdateAvailable -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.about_update_avail_fmt, st.latestVersion),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (st.isPrerelease) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.about_update_prerelease_tag),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            if (st.releaseNotes.isNotBlank()) {
                                Text(
                                    text = st.releaseNotes.lines().filter { it.isNotBlank() }.take(3).joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = {
                                    if (!st.apkDownloadUrl.isNullOrBlank()) {
                                        onDownloadUpdate(st)
                                    } else {
                                        onOpenUpdateUrl(st.releaseUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        if (!st.apkDownloadUrl.isNullOrBlank()) {
                                            R.string.about_update_download_btn
                                        } else {
                                            R.string.about_update_browser_btn
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                is UpdateCheckState.Downloading -> {
                    val hasTotal = st.totalBytes > 0L
                    val progress = if (hasTotal) {
                        (st.downloadedBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.about_update_downloading_fmt, st.latestVersion),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = if (hasTotal) {
                                            stringResource(
                                                R.string.about_update_progress_fmt,
                                                UpdateChecker.formatBytesMb(st.downloadedBytes),
                                                UpdateChecker.formatBytesMb(st.totalBytes),
                                                pct
                                            )
                                        } else {
                                            UpdateChecker.formatBytesMb(st.downloadedBytes)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                                TextButton(
                                    onClick = onCancelUpdateDownload,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.about_update_cancel_btn),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            if (hasTotal) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                is UpdateCheckState.ReadyToInstall -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.about_update_ready_fmt, st.latestVersion),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Button(
                                onClick = { onInstallReadyApk(st.apkPath) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.about_update_install_now_btn),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                is UpdateCheckState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
                    ) {
                        Text(
                            text = stringResource(R.string.about_update_error_fmt, st.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ==================== Shared Material 3 Building Blocks ====================
/**
 * Shared 6-color drawer accent palette matching [com.autodora.next.overlay.OverlayTuningDrawer] 1-to-1.
 */
internal object DrawerAccentColors {
    val Indigo = Color(0xFF3F51B5)
    val Teal = Color(0xFF00897B)
    val Amber = Color(0xFFE67600)
    val Crimson = Color(0xFFD32F2F)
    val Blue = Color(0xFF1976D2)
    val Purple = Color(0xFF7B1FA2)
}

/**
 * Colored collapsible drawer card matching the floating overlay drawer's color-accented visual design.
 * Launch-page drawers default to expanded ([initialExpanded] = true) and can be expanded/collapsed
 * independently so multiple drawers remain open simultaneously.
 */
@Composable
internal fun ColoredCollapsibleDrawerCard(
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    summaryText: String? = null,
    highlighted: Boolean = false,
    initialExpanded: Boolean = true,
    onHelp: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initialExpanded) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val borderColor = if (expanded || highlighted) {
        accentColor.copy(alpha = if (isDark) 0.65f else 0.52f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val headerTint = accentColor.copy(alpha = if (isDark) 0.18f else 0.10f)
    val dividerColor = accentColor.copy(alpha = if (isDark) 0.24f else 0.16f)
    val summaryBg = accentColor.copy(alpha = if (isDark) 0.22f else 0.12f)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(if (expanded || highlighted) 1.25.dp else 1.dp, borderColor),
        tonalElevation = if (isDark) 1.dp else 0.dp,
        shadowElevation = if (isDark) 0.dp else 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerTint)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!summaryText.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (highlighted) summaryBg else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (highlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 150.dp)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                if (onHelp != null) {
                    Spacer(Modifier.width(6.dp))
                    HelpCircleButton(onClick = onHelp)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
internal fun SectionHeaderWithHelp(text: String, onHelp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        HelpCircleButton(onClick = onHelp)
    }
}

@Composable
private fun InfoPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PermissionItemRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    allowAlwaysClick: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        val readyBg = MaterialTheme.colorScheme.surfaceContainerHighest
        val readyFg = MaterialTheme.colorScheme.onSurfaceVariant
        FilledTonalButton(
            onClick = onAction,
            enabled = !granted || allowAlwaysClick,
            colors = if (granted) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = readyBg,
                    contentColor = readyFg,
                    disabledContainerColor = readyBg,
                    disabledContentColor = readyFg
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                if (granted) stringResource(R.string.perm_ready) else actionLabel,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Clean native-Android style slim SeekBar (4dp track + 18dp circular thumb) instead of
 * Material 3 1.3's oversized 16dp thick bar and vertical line thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimNativeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumbBorder = MaterialTheme.colorScheme.surface

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(2.dp, thumbBorder, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = ((sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(1e-4f))
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(inactiveColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(activeColor)
                )
            }
        }
    )
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        SlimNativeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
