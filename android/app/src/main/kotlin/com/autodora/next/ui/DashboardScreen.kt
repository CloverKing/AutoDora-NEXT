package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    SHIELDS(R.string.tab_shields),
    SETTINGS(R.string.tab_settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    health: PermissionHealthState,
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onDuplicatePreset: (TeamBasePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>
) {
    val ctx = LocalContext.current
    var activeTab by remember { mutableStateOf(HomeTab.OVERVIEW) }
    var editingPreset by remember { mutableStateOf<Pair<TeamBasePreset, Boolean>?>(null) }

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
                        HomeTab.SHIELDS -> Icons.Outlined.Shield
                        HomeTab.SETTINGS -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { activeTab = tab },
                        icon = {
                            if (tab == HomeTab.SHIELDS && activeWaveChips.isNotEmpty()) {
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

                HomeTab.STRATEGIES -> StrategiesTab(
                    presets = presets,
                    activePresetId = settings.activePresetId,
                    onSelectPreset = onSelectPreset,
                    onCreateNew = {
                        editingPreset = TeamBasePreset(id = "", name = "", description = "") to false
                    },
                    onEdit = { rec -> editingPreset = rec.preset to rec.isBuiltIn },
                    onDuplicate = onDuplicatePreset,
                    onDelete = onDeletePreset
                )

                HomeTab.SHIELDS -> ShieldsTab(
                    settings = settings,
                    activeWaveChips = activeWaveChips,
                    lastOcrSummary = lastOcrSummary,
                    onUpdateSettings = onUpdateSettings,
                    onToggleWaveChip = onToggleWaveChip,
                    onClearWaveChips = onClearWaveChips,
                    onParseSkillText = onParseSkillText
                )

                HomeTab.SETTINGS -> SettingsTab(
                    settings = settings,
                    benchmarkReport = benchmarkReport,
                    isBenchmarking = isBenchmarking,
                    onUpdateSettings = onUpdateSettings,
                    onRunNativeBenchmark = onRunNativeBenchmark
                )
            }
        }
    }

    editingPreset?.let { (preset, isBuiltIn) ->
        TeamPresetEditorDialog(
            initial = preset,
            isBuiltIn = isBuiltIn,
            onDismiss = { editingPreset = null },
            onConfirm = { updated ->
                onSavePreset(updated, isBuiltIn)
                editingPreset = null
            }
        )
    }
}

// ==================== Tab 0: Overview Hub ====================
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
    var showHyperOsGuide by remember { mutableStateOf(false) }
    val speedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Launch & Live Strategy Card
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.overview_current_strategy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activePresetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                        onClick = { onNavigateTab(HomeTab.SHIELDS) },
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
        }

        // Core Permissions Checklist
        SectionHeader(stringResource(R.string.section_permissions))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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

        // Quick Usage Guide
        OutlinedCard(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.guide_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
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

        // Collapsible HyperOS / Restricted Settings Guide
        OutlinedCard(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.hyperos_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = onOpenAppDetails) {
                            Text(
                                stringResource(R.string.hyperos_app_details),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        TextButton(onClick = { showHyperOsGuide = !showHyperOsGuide }) {
                            Text(
                                stringResource(if (showHyperOsGuide) R.string.action_collapse else R.string.action_expand),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = showHyperOsGuide) {
                    Text(
                        text = stringResource(R.string.hyperos_guide_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

// ==================== Tab 1: Room Strategy / Goal Profiles ====================
@Composable
private fun StrategiesTab(
    presets: List<TeamPresetRecord>,
    activePresetId: String,
    onSelectPreset: (String) -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (TeamPresetRecord) -> Unit,
    onDuplicate: (TeamBasePreset) -> Unit,
    onDelete: (String) -> Unit
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = stringResource(R.string.strategies_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.strategies_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onCreateNew) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_new_strategy))
            }
        }

        val items = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in items) {
            val p = rec.preset
            val cfg = p.baseConfig
            val isActive = p.id == activePresetId
            val displayName = LocaleHelper.displayPresetName(ctx, p, rec.isBuiltIn)
            val displayDesc = LocaleHelper.displayPresetDesc(ctx, p, rec.isBuiltIn)
            val tags = buildList {
                add(LocaleHelper.goalModeLabel(ctx, cfg.goalMode))
                add(ctx.getString(R.string.tag_steps_fmt, cfg.maxSteps))
                add(ctx.getString(R.string.tag_beam_fmt, cfg.beamWidth))
                if (cfg.earlyStopOnGoal) add(ctx.getString(R.string.tag_first_result))
                if (cfg.forceSplitThree) add(ctx.getString(R.string.tag_split_three))
                if (cfg.firstWaveOnlyScoring) add(ctx.getString(R.string.tag_first_only))
                if (cfg.elementRules.any { it.minMatchCount == 2 }) add(ctx.getString(R.string.tag_two_match))
                if (cfg.elementRules.any { it.targetShape != TargetShape.ANY }) add(ctx.getString(R.string.tag_custom_shape))
                if (cfg.columnRules.any { it.enabled }) add(ctx.getString(R.string.tag_col_amel))
                if (cfg.useCharacterOrb) add(ctx.getString(R.string.tag_char_orb_fmt, cfg.characterOrbCol + 1))
                if (cfg.startPreset == StartPointPreset.BOTTOM_LEFT) add(ctx.getString(R.string.tag_start_bl))
                if (cfg.startPreset == StartPointPreset.BOTTOM_RIGHT) add(ctx.getString(R.string.tag_start_br))
                if (cfg.allowDiagonalMatch) add(ctx.getString(R.string.tag_diag_match))
            }

            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = if (isActive) {
                    CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.elevatedCardColors()
                }
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isActive) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            stringResource(R.string.badge_active),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                            if (displayDesc.isNotBlank()) {
                                Text(
                                    text = displayDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isActive) {
                            Button(
                                onClick = { onSelectPreset(p.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.btn_enable), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalButton(
                            onClick = { onEdit(rec) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.btn_edit_strategy), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { onDuplicate(p.copy(name = displayName, description = displayDesc)) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.btn_duplicate), style = MaterialTheme.typography.labelMedium)
                        }
                        if (!rec.isBuiltIn) {
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { onDelete(p.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.btn_delete), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Tab 2: Abstract Parameterized Shield Controls & Auto Enemy Skill Engine ====================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShieldsTab(
    settings: AppSettings,
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>
) {
    val ctx = LocalContext.current
    var testSkillText by remember { mutableStateOf("") }
    var showRuleTester by remember { mutableStateOf(false) }

    val byId = remember(activeWaveChips) { activeWaveChips.associateBy { it.id } }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }

    // 1. Independent Per-Type Combo Shield State (never collides across shield types!)
    var minTotalComboVal by remember { mutableStateOf(byId["min_combo"]?.paramInt ?: 6) }
    var minFirstComboVal by remember { mutableStateOf(byId["min_first_combo"]?.paramInt ?: 5) }
    var exactTotalComboVal by remember { mutableStateOf(byId["exact_combo"]?.paramInt ?: 6) }
    var exactFirstComboVal by remember { mutableStateOf(byId["exact_first_combo"]?.paramInt ?: 4) }
    var parityFirstOnly by remember {
        mutableStateOf((byId["odd_combo"]?.paramInt ?: byId["even_combo"]?.paramInt ?: 0) != 1)
    }
    var secondWaveVal by remember { mutableStateOf(byId["second_wave_combo"]?.paramInt ?: 1) }
    var sameElemGroupsVal by remember { mutableStateOf(byId["same_elem_combos"]?.paramInt ?: 3) }
    val initialPairPacked = byId["equal_pair_light_dark"]?.paramElementMask ?: ((4 shl 4) or 3)
    var pairElemA by remember { mutableStateOf((initialPairPacked and 0x0F).coerceIn(0, 5)) }
    var pairElemB by remember { mutableStateOf(((initialPairPacked ushr 4) and 0x0F).coerceIn(0, 5)) }

    // Sync local steppers if OCR imported a combo shield externally
    LaunchedEffect(byId["min_combo"]?.paramInt) {
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
    }
    LaunchedEffect(byId["min_first_combo"]?.paramInt) {
        byId["min_first_combo"]?.paramInt?.let { if (it in 1..15) minFirstComboVal = it }
    }
    LaunchedEffect(byId["exact_combo"]?.paramInt) {
        byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it }
    }
    LaunchedEffect(byId["exact_first_combo"]?.paramInt) {
        byId["exact_first_combo"]?.paramInt?.let { if (it in 1..15) exactFirstComboVal = it }
    }

    // 2. Element Restriction sub-tab (0 = Anti-1st, 1 = Forbid All, 2 = Clear All 1st)
    var elemLimitMode by remember { mutableStateOf(0) }
    var digitalOrbs by remember { mutableStateOf(byId["digital_orb_count"]?.paramInt ?: 18) }

    // 3. Path & Step state
    var maxBurnHurts by remember {
        mutableStateOf(((byId["zero_trail_burning"]?.paramInt ?: 0) ushr 8) and 0xFF)
    }
    var burnTrailLen by remember {
        mutableStateOf((byId["zero_trail_burning"]?.paramInt ?: 0) and 0xFF)
    }
    var stepLimitVal by remember { mutableStateOf(byId["step_limit"]?.paramInt ?: 15) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== 0. Auto Enemy Skill Intelligence Hub ====================
        SectionHeader(stringResource(R.string.section_auto_skill))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SwitchSettingRow(
                    title = stringResource(R.string.auto_skill_title),
                    subtitle = stringResource(R.string.auto_skill_sub),
                    checked = settings.autoEnemySkillEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoEnemySkillEnabled = v) } }
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EnemySkillScanMode.entries.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.enemySkillScanMode == mode,
                            onClick = { onUpdateSettings { it.copy(enemySkillScanMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(idx, EnemySkillScanMode.entries.size)
                        ) {
                            Text(
                                LocaleHelper.scanModeLabel(ctx, mode),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
                Text(
                    text = LocaleHelper.scanModeDesc(ctx, settings.enemySkillScanMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Active composed wave shields summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.active_shields_fmt, activeWaveChips.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showRuleTester = !showRuleTester }) {
                            Text(
                                stringResource(
                                    if (showRuleTester) R.string.btn_rule_tester_close else R.string.btn_rule_tester_open
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (activeWaveChips.isNotEmpty()) {
                            TextButton(onClick = onClearWaveChips) {
                                Text(
                                    stringResource(R.string.btn_clear),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                if (activeWaveChips.isEmpty()) {
                    Text(
                        text = stringResource(R.string.active_shields_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (chip in activeWaveChips) {
                            InputChip(
                                selected = true,
                                onClick = { onToggleWaveChip(chip) },
                                label = { Text(chip.label, style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
                if (!lastOcrSummary.isNullOrBlank()) {
                    Text(
                        text = lastOcrSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = showRuleTester) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = testSkillText,
                            onValueChange = { testSkillText = it },
                            label = { Text(stringResource(R.string.rule_tester_hint)) },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { testSkillText = "【固定 4 連擊盾】\n首批消除 4 連擊 (Combo)\n五屬強化盾\n拼圖盾-限" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.btn_sample), style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { if (testSkillText.isNotBlank()) onParseSkillText(testSkillText) },
                                enabled = testSkillText.isNotBlank()
                            ) {
                                Text(stringResource(R.string.btn_parse_import))
                            }
                        }
                    }
                }
            }
        }

        // ==================== 1. Combo Shield (Independent Per-Type Parameters!) ====================
        SectionHeader(stringResource(R.string.section_combo_shield))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val curComboMode = when {
                    "min_combo" in activeIds -> "min_combo"
                    "min_first_combo" in activeIds -> "min_first_combo"
                    "exact_combo" in activeIds -> "exact_combo"
                    "exact_first_combo" in activeIds -> "exact_first_combo"
                    "zero_combo" in activeIds -> "zero_combo"
                    "odd_combo" in activeIds -> "odd_combo"
                    "even_combo" in activeIds -> "even_combo"
                    else -> "none"
                }

                fun applyComboType(mode: String) {
                    val chip = when (mode) {
                        "min_combo" -> WaveShieldChip(
                            "min_combo",
                            "${ctx.getString(R.string.combo_param_min_total)} ≥${minTotalComboVal}C",
                            ShieldCategory.COMBO,
                            paramInt = minTotalComboVal
                        )
                        "min_first_combo" -> WaveShieldChip(
                            "min_first_combo",
                            "${ctx.getString(R.string.combo_param_min_first)} ≥${minFirstComboVal}C",
                            ShieldCategory.COMBO,
                            paramInt = minFirstComboVal
                        )
                        "exact_combo" -> WaveShieldChip(
                            "exact_combo",
                            "${ctx.getString(R.string.combo_param_exact_total)} ${exactTotalComboVal}C",
                            ShieldCategory.COMBO,
                            paramInt = exactTotalComboVal
                        )
                        "exact_first_combo" -> WaveShieldChip(
                            "exact_first_combo",
                            "${ctx.getString(R.string.combo_param_exact_first)} ${exactFirstComboVal}C",
                            ShieldCategory.COMBO,
                            paramInt = exactFirstComboVal
                        )
                        "zero_combo" -> WaveShieldChip(
                            "zero_combo",
                            ctx.getString(R.string.combo_type_zero),
                            ShieldCategory.COMBO
                        )
                        "odd_combo" -> {
                            val scope = ctx.getString(
                                if (parityFirstOnly) R.string.combo_parity_scope_first else R.string.combo_parity_scope_total
                            )
                            WaveShieldChip(
                                "odd_combo",
                                "${ctx.getString(R.string.combo_type_odd)} ($scope)",
                                ShieldCategory.COMBO,
                                paramInt = if (parityFirstOnly) 0 else 1
                            )
                        }
                        "even_combo" -> {
                            val scope = ctx.getString(
                                if (parityFirstOnly) R.string.combo_parity_scope_first else R.string.combo_parity_scope_total
                            )
                            WaveShieldChip(
                                "even_combo",
                                "${ctx.getString(R.string.combo_type_even)} ($scope)",
                                ShieldCategory.COMBO,
                                paramInt = if (parityFirstOnly) 0 else 1
                            )
                        }
                        else -> null
                    }
                    ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
                }

                // Clean Type Selector (never mutates other types' numbers!)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "none" to stringResource(R.string.combo_type_none),
                        "min_combo" to stringResource(R.string.combo_type_min_total),
                        "min_first_combo" to stringResource(R.string.combo_type_min_first),
                        "exact_combo" to stringResource(R.string.combo_type_exact_total),
                        "exact_first_combo" to stringResource(R.string.combo_type_exact_first),
                        "zero_combo" to stringResource(R.string.combo_type_zero),
                        "odd_combo" to stringResource(R.string.combo_type_odd),
                        "even_combo" to stringResource(R.string.combo_type_even)
                    ).forEach { (id, label) ->
                        CompactChoicePill(
                            label = label,
                            selected = curComboMode == id,
                            onClick = { applyComboType(id) }
                        )
                    }
                }

                // Dedicated Parameter Panel for ONLY the Selected Combo Shield Type
                if (curComboMode != "none" && curComboMode != "zero_combo") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (curComboMode) {
                                "min_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_total),
                                    valueText = "≥ $minTotalComboVal Combo",
                                    onMinus = {
                                        minTotalComboVal = (minTotalComboVal - 1).coerceIn(1, 15)
                                        applyComboType("min_combo")
                                    },
                                    onPlus = {
                                        minTotalComboVal = (minTotalComboVal + 1).coerceIn(1, 15)
                                        applyComboType("min_combo")
                                    }
                                )
                                "min_first_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_first),
                                    valueText = "≥ $minFirstComboVal Combo",
                                    onMinus = {
                                        minFirstComboVal = (minFirstComboVal - 1).coerceIn(1, 12)
                                        applyComboType("min_first_combo")
                                    },
                                    onPlus = {
                                        minFirstComboVal = (minFirstComboVal + 1).coerceIn(1, 12)
                                        applyComboType("min_first_combo")
                                    }
                                )
                                "exact_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_exact_total),
                                    valueText = "$exactTotalComboVal Combo",
                                    onMinus = {
                                        exactTotalComboVal = (exactTotalComboVal - 1).coerceIn(1, 15)
                                        applyComboType("exact_combo")
                                    },
                                    onPlus = {
                                        exactTotalComboVal = (exactTotalComboVal + 1).coerceIn(1, 15)
                                        applyComboType("exact_combo")
                                    }
                                )
                                "exact_first_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_exact_first),
                                    valueText = "$exactFirstComboVal Combo",
                                    onMinus = {
                                        exactFirstComboVal = (exactFirstComboVal - 1).coerceIn(1, 12)
                                        applyComboType("exact_first_combo")
                                    },
                                    onPlus = {
                                        exactFirstComboVal = (exactFirstComboVal + 1).coerceIn(1, 12)
                                        applyComboType("exact_first_combo")
                                    }
                                )
                                "odd_combo", "even_combo" -> Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.combo_parity_scope_first),
                                        selected = parityFirstOnly,
                                        onClick = {
                                            parityFirstOnly = true
                                            applyComboType(curComboMode)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactChoicePill(
                                        label = stringResource(R.string.combo_parity_scope_total),
                                        selected = !parityFirstOnly,
                                        onClick = {
                                            parityFirstOnly = false
                                            applyComboType(curComboMode)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Composable Additional Combo Rules
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.combo_extra_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                fun upsertSecondWave(n: Int) {
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            "second_wave_combo",
                            ctx.getString(R.string.combo_extra_second_wave, n),
                            ShieldCategory.COMBO,
                            paramInt = n
                        )
                    )
                }

                fun upsertSameElem(n: Int) {
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            "same_elem_combos",
                            ctx.getString(R.string.combo_extra_same_elem, n),
                            ShieldCategory.COMBO,
                            paramInt = n
                        )
                    )
                }

                fun upsertEqualPair(a: Int, b: Int) {
                    val packed = (a and 0x0F) or ((b and 0x0F) shl 4)
                    val la = LocaleHelper.elementLabel(ctx, a)
                    val lb = LocaleHelper.elementLabel(ctx, b)
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            "equal_pair_light_dark",
                            ctx.getString(R.string.combo_extra_equal_pair, la, lb),
                            ShieldCategory.COMBO,
                            paramElementMask = packed
                        )
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val fwoLabel = stringResource(R.string.combo_extra_first_only)
                    CompactChoicePill(
                        label = fwoLabel,
                        selected = "first_wave_only" in activeIds,
                        onClick = {
                            onToggleWaveChip(WaveShieldChip("first_wave_only", fwoLabel, ShieldCategory.COMBO))
                        }
                    )
                    val sameCLabel = stringResource(R.string.combo_extra_same_c)
                    CompactChoicePill(
                        label = sameCLabel,
                        selected = "same_combo_elements" in activeIds,
                        onClick = {
                            onToggleWaveChip(WaveShieldChip("same_combo_elements", sameCLabel, ShieldCategory.COMBO))
                        }
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.combo_extra_second_wave, secondWaveVal),
                        selected = "second_wave_combo" in activeIds,
                        onClick = {
                            if ("second_wave_combo" in activeIds) ActiveStrategyStore.removeChip("second_wave_combo")
                            else upsertSecondWave(secondWaveVal)
                        }
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.combo_extra_same_elem, sameElemGroupsVal),
                        selected = "same_elem_combos" in activeIds,
                        onClick = {
                            if ("same_elem_combos" in activeIds) ActiveStrategyStore.removeChip("same_elem_combos")
                            else upsertSameElem(sameElemGroupsVal)
                        }
                    )
                    val pairLabel = stringResource(
                        R.string.combo_extra_equal_pair,
                        LocaleHelper.elementLabel(ctx, pairElemA),
                        LocaleHelper.elementLabel(ctx, pairElemB)
                    )
                    CompactChoicePill(
                        label = pairLabel,
                        selected = "equal_pair_light_dark" in activeIds,
                        onClick = {
                            if ("equal_pair_light_dark" in activeIds) ActiveStrategyStore.removeChip("equal_pair_light_dark")
                            else upsertEqualPair(pairElemA, pairElemB)
                        }
                    )
                }

                if ("second_wave_combo" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.combo_second_wave_step),
                        valueText = "≥ $secondWaveVal Combo",
                        onMinus = {
                            secondWaveVal = (secondWaveVal - 1).coerceIn(1, 6)
                            upsertSecondWave(secondWaveVal)
                        },
                        onPlus = {
                            secondWaveVal = (secondWaveVal + 1).coerceIn(1, 6)
                            upsertSecondWave(secondWaveVal)
                        }
                    )
                }
                if ("same_elem_combos" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.combo_same_elem_step),
                        valueText = "$sameElemGroupsVal Combo",
                        onMinus = {
                            sameElemGroupsVal = (sameElemGroupsVal - 1).coerceIn(2, 6)
                            upsertSameElem(sameElemGroupsVal)
                        },
                        onPlus = {
                            sameElemGroupsVal = (sameElemGroupsVal + 1).coerceIn(2, 6)
                            upsertSameElem(sameElemGroupsVal)
                        }
                    )
                }
                if ("equal_pair_light_dark" in activeIds) {
                    Text(
                        text = stringResource(R.string.combo_equal_pair_pick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        for (e in 0 until 6) {
                            val selected = e == pairElemA || e == pairElemB
                            ElementColorTogglePill(
                                elemIndex = e,
                                selected = selected,
                                onClick = {
                                    if (e != pairElemA && e != pairElemB) {
                                        pairElemA = pairElemB
                                        pairElemB = e
                                        upsertEqualPair(pairElemA, pairElemB)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ==================== 2. Element & Count Shield (100% Parameterized — Zero Hardcoded Colors!) ====================
        SectionHeader(stringResource(R.string.section_elem_count_shield))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2a. Min Distinct Attributes (不限 / ≥2属 / ≥3属 / ≥4属 / ≥5属 / ≥6属)
                Text(
                    text = stringResource(R.string.elem_min_distinct_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val curMinDistinct = byId["min_distinct_elements"]?.paramInt ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (cnt in listOf(0, 2, 3, 4, 5, 6)) {
                        val lbl = if (cnt == 0) {
                            stringResource(R.string.elem_min_distinct_any)
                        } else {
                            stringResource(R.string.elem_min_distinct_fmt, cnt)
                        }
                        CompactChoicePill(
                            label = lbl,
                            selected = curMinDistinct == cnt,
                            onClick = {
                                if (cnt == 0) {
                                    ActiveStrategyStore.removeChip("min_distinct_elements")
                                } else {
                                    ActiveStrategyStore.upsertChip(
                                        WaveShieldChip(
                                            id = "min_distinct_elements",
                                            label = ctx.getString(R.string.elem_min_distinct_fmt, cnt),
                                            category = ShieldCategory.COUNT_AND_ELEMENT,
                                            paramInt = cnt
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2b. Required Attributes (Multi-Select ANY Subset of 6 Colors + Independent Enchanted Switch!)
                Text(
                    text = stringResource(R.string.elem_req_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Consolidate mask from require_elements + any OCR-imported legacy chips (five_elements / require_elem_e)
                val reqMaskFromChip = byId["require_elements"]?.paramElementMask ?: 0
                val reqEnchFromChip = (byId["require_elements"]?.paramInt ?: 0) == 1 ||
                    "five_elements_enchanted" in activeIds ||
                    (0..5).any { "require_ench_$it" in activeIds }
                val effectiveReqMask = remember(activeWaveChips) {
                    var m = reqMaskFromChip
                    if ("three_elements" in activeIds) m = m or 0x07
                    if ("five_elements" in activeIds || "five_elements_enchanted" in activeIds) m = m or 0x1F
                    for (e in 0..5) {
                        if ("require_elem_$e" in activeIds || "require_ench_$e" in activeIds) m = m or (1 shl e)
                    }
                    m
                }

                fun saveRequiredElements(newMask: Int, requireEnch: Boolean) {
                    // Clean up legacy OCR aliases so UI remains the single source of truth
                    val legacyIds = buildSet {
                        add("require_elements")
                        add("three_elements")
                        add("five_elements")
                        add("five_elements_enchanted")
                        for (e in 0..5) {
                            add("require_elem_$e")
                            add("require_ench_$e")
                        }
                    }
                    if (newMask == 0) {
                        ActiveStrategyStore.replaceGroup(legacyIds, null)
                        return
                    }
                    val names = (0..5).filter { (newMask and (1 shl it)) != 0 }
                        .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                    val enchSuffix = if (requireEnch) "+" else ""
                    val chip = WaveShieldChip(
                        id = "require_elements",
                        label = "$names$enchSuffix",
                        category = ShieldCategory.COUNT_AND_ELEMENT,
                        paramInt = if (requireEnch) 1 else 0,
                        paramElementMask = newMask
                    )
                    ActiveStrategyStore.replaceGroup(legacyIds, chip)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (e in 0 until 6) {
                        val isOn = (effectiveReqMask and (1 shl e)) != 0
                        ElementColorTogglePill(
                            elemIndex = e,
                            selected = isOn,
                            suffix = if (isOn && reqEnchFromChip) "+" else "",
                            onClick = {
                                val nextMask = effectiveReqMask xor (1 shl e)
                                saveRequiredElements(nextMask, reqEnchFromChip)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.elem_req_ench_switch),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (effectiveReqMask != 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Switch(
                        checked = reqEnchFromChip && effectiveReqMask != 0,
                        enabled = effectiveReqMask != 0,
                        onCheckedChange = { ench -> saveRequiredElements(effectiveReqMask, ench) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2c. Attribute Restrictions (Anti-1st / Forbid All / Clear All 1st — Any Colors!)
                Text(
                    text = stringResource(R.string.elem_forbid_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                fun maskForLimit(baseId: String): Int {
                    var m = byId[baseId]?.paramElementMask ?: 0
                    for (e in 0..5) {
                        if ("${baseId}_$e" in activeIds) m = m or (1 shl e)
                    }
                    return m
                }

                val antiMask = maskForLimit("anti_first_wave")
                val forbidMask = maskForLimit("forbid_all")
                val clearMask = maskForLimit("clear_all_element")

                val antiCnt = Integer.bitCount(antiMask)
                val forbidCnt = Integer.bitCount(forbidMask)
                val clearCnt = Integer.bitCount(clearMask)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple(0, stringResource(R.string.elem_limit_anti), antiCnt),
                        Triple(1, stringResource(R.string.elem_limit_forbid), forbidCnt),
                        Triple(2, stringResource(R.string.elem_limit_clear_all), clearCnt)
                    ).forEach { (idx, lbl, cnt) ->
                        CompactChoicePill(
                            label = if (cnt > 0) "$lbl ($cnt)" else lbl,
                            selected = elemLimitMode == idx,
                            onClick = { elemLimitMode = idx },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val activeLimitBaseId = when (elemLimitMode) {
                    0 -> "anti_first_wave"
                    1 -> "forbid_all"
                    else -> "clear_all_element"
                }
                val activeLimitPrefix = when (elemLimitMode) {
                    0 -> stringResource(R.string.elem_limit_anti)
                    1 -> stringResource(R.string.elem_limit_forbid)
                    else -> stringResource(R.string.elem_limit_clear_all)
                }
                val curLimitMask = when (elemLimitMode) {
                    0 -> antiMask
                    1 -> forbidMask
                    else -> clearMask
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (e in 0 until 6) {
                        val isOn = (curLimitMask and (1 shl e)) != 0
                        ElementColorTogglePill(
                            elemIndex = e,
                            selected = isOn,
                            onClick = {
                                val nextMask = curLimitMask xor (1 shl e)
                                val legacySet = buildSet {
                                    add(activeLimitBaseId)
                                    for (i in 0..5) add("${activeLimitBaseId}_$i")
                                }
                                if (nextMask == 0) {
                                    ActiveStrategyStore.replaceGroup(legacySet, null)
                                } else {
                                    val names = (0..5).filter { (nextMask and (1 shl it)) != 0 }
                                        .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                                    ActiveStrategyStore.replaceGroup(
                                        legacySet,
                                        WaveShieldChip(
                                            id = activeLimitBaseId,
                                            label = "$activeLimitPrefix:$names",
                                            category = ShieldCategory.COUNT_AND_ELEMENT,
                                            paramElementMask = nextMask
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2d. Orb Count & Race Shields
                Text(
                    text = stringResource(R.string.elem_count_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val digLabel = stringResource(R.string.shield_digital_orbs_fmt, digitalOrbs)
                    CompactChoicePill(
                        label = digLabel,
                        selected = "digital_orb_count" in activeIds,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("digital_orb_count", digLabel, ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs)
                            )
                        }
                    )
                    val fullClearLabel = stringResource(R.string.shield_full_clear)
                    CompactChoicePill(
                        label = fullClearLabel,
                        selected = "first_wave_full_clear" in activeIds,
                        onClick = {
                            onToggleWaveChip(WaveShieldChip("first_wave_full_clear", fullClearLabel, ShieldCategory.COUNT_AND_ELEMENT))
                        }
                    )
                    val raceLabel = stringResource(R.string.shield_race_orb)
                    CompactChoicePill(
                        label = raceLabel,
                        selected = "race_orb_shield" in activeIds,
                        onClick = {
                            onToggleWaveChip(WaveShieldChip("race_orb_shield", raceLabel, ShieldCategory.COUNT_AND_ELEMENT))
                        }
                    )
                }
                if ("digital_orb_count" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.shield_digital_orbs_step),
                        valueText = "$digitalOrbs ${stringResource(R.string.unit_orbs)}",
                        onMinus = {
                            digitalOrbs = (digitalOrbs - 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "digital_orb_count",
                                    ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs),
                                    ShieldCategory.COUNT_AND_ELEMENT,
                                    paramInt = digitalOrbs
                                )
                            )
                        },
                        onPlus = {
                            digitalOrbs = (digitalOrbs + 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "digital_orb_count",
                                    ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs),
                                    ShieldCategory.COUNT_AND_ELEMENT,
                                    paramInt = digitalOrbs
                                )
                            )
                        }
                    )
                }
            }
        }

        // ==================== 3. Puzzle & Shape Shield (Mode + Enchanted + Element + Shape!) ====================
        SectionHeader(stringResource(R.string.section_puzzle_shape_shield))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Decode current Puzzle Shield state from unified "puzzle_shield" OR legacy OCR chips
                val pChip = byId["puzzle_shield"]
                val puzzleMode = when {
                    pChip != null -> if ((pChip.paramInt and 1) != 0) 2 else 1
                    "strict_puzzle" in activeIds || "enchanted_puzzle" in activeIds -> 2
                    "loose_puzzle" in activeIds -> 1
                    else -> 0
                }
                val puzzleEnch = when {
                    pChip != null -> (pChip.paramInt and 2) != 0
                    "enchanted_puzzle" in activeIds -> true
                    else -> false
                }
                val puzzleElemEnc = if (pChip != null) (pChip.paramInt ushr 2) and 0x0F else 0
                val puzzleElem = if (puzzleElemEnc in 1..6) puzzleElemEnc - 1 else -1
                val puzzleShapeId = if (pChip != null) (pChip.paramInt ushr 8) and 0x0F else 0

                fun savePuzzle(mode: Int, ench: Boolean, elem: Int, shapeId: Int) {
                    if (mode == 0) {
                        ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null)
                        return
                    }
                    val strictBit = if (mode == 2) 1 else 0
                    val enchBit = if (ench) 2 else 0
                    val elemBits = if (elem in 0..5) ((elem + 1) shl 2) else 0
                    val shapeBits = (shapeId and 0x0F) shl 8
                    val packed = strictBit or enchBit or elemBits or shapeBits

                    val modeName = ctx.getString(if (mode == 2) R.string.ov_puzzle_strict else R.string.ov_puzzle_normal)
                    val parts = buildList {
                        add(modeName)
                        if (ench) add(ctx.getString(R.string.ov_puzzle_ench))
                        if (elem in 0..5) add(LocaleHelper.elementLabel(ctx, elem))
                        if (shapeId > 0) add(LocaleHelper.puzzleShapeLabel(ctx, shapeId))
                    }
                    ActiveStrategyStore.replaceGroup(
                        WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS,
                        WaveShieldChip(
                            id = "puzzle_shield",
                            label = parts.joinToString("·"),
                            category = ShieldCategory.SHAPE_AND_PUZZLE,
                            paramInt = packed
                        )
                    )
                }

                // Step 1: Puzzle Mode (Off / Normal / Strict)
                Text(
                    text = stringResource(R.string.puzzle_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_off),
                        selected = puzzleMode == 0,
                        onClick = { savePuzzle(0, false, -1, 0) }
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_normal),
                        selected = puzzleMode == 1,
                        onClick = { savePuzzle(1, puzzleEnch, puzzleElem, puzzleShapeId) }
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_strict),
                        selected = puzzleMode == 2,
                        onClick = { savePuzzle(2, puzzleEnch, puzzleElem, puzzleShapeId) }
                    )
                }

                // Steps 2, 3, 4 revealed cleanly when Puzzle Mode is enabled
                AnimatedVisibility(visible = puzzleMode > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Step 2: Independent Enchanted Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.puzzle_ench_switch),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Switch(
                                    checked = puzzleEnch,
                                    onCheckedChange = { savePuzzle(puzzleMode, it, puzzleElem, puzzleShapeId) }
                                )
                            }

                            // Step 3: Optional Puzzle Attribute
                            Text(
                                text = stringResource(R.string.puzzle_elem_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CompactChoicePill(
                                    label = stringResource(R.string.puzzle_elem_any),
                                    selected = puzzleElem < 0,
                                    onClick = { savePuzzle(puzzleMode, puzzleEnch, -1, puzzleShapeId) },
                                    modifier = Modifier.weight(1.65f).height(34.dp)
                                )
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = puzzleElem == e,
                                        onClick = { savePuzzle(puzzleMode, puzzleEnch, e, puzzleShapeId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            // Step 4: Puzzle Shape Source (Auto Golden Frame OR Preset Shape Template!)
                            Text(
                                text = stringResource(R.string.puzzle_shape_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (shapeId in 0..6) {
                                    CompactChoicePill(
                                        label = LocaleHelper.puzzleShapeLabel(ctx, shapeId),
                                        selected = puzzleShapeId == shapeId,
                                        onClick = { savePuzzle(puzzleMode, puzzleEnch, puzzleElem, shapeId) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Standalone Shape Shields (Non-Puzzle)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.shape_shield_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val crossRaw = byId["cross_shield"]?.paramInt
                    ?: byId["cross_shield_2"]?.paramInt
                    ?: byId["cross_shield_3"]?.paramInt
                    ?: 0
                val curCrossCount = crossRaw and 0x0F
                val curCrossDistinct = (crossRaw and 0x20) != 0

                fun saveCross(count: Int, distinct: Boolean) {
                    if (count <= 0) {
                        ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, null)
                        return
                    }
                    val packed = (count and 0x0F) or (if (distinct && count >= 2) 0x20 else 0)
                    val baseLbl = when (count) {
                        1 -> ctx.getString(R.string.cross_1)
                        2 -> ctx.getString(R.string.cross_2)
                        else -> ctx.getString(R.string.cross_3)
                    }
                    ActiveStrategyStore.replaceGroup(
                        WaveShieldCatalog.CROSS_EXCLUSIVE_IDS,
                        WaveShieldChip(
                            id = "cross_shield",
                            label = if (distinct && count >= 2) "$baseLbl(≠)" else baseLbl,
                            category = ShieldCategory.SHAPE_AND_PUZZLE,
                            paramInt = packed
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(
                        0 to stringResource(R.string.cross_off),
                        1 to stringResource(R.string.cross_1),
                        2 to stringResource(R.string.cross_2),
                        3 to stringResource(R.string.cross_3)
                    ).forEach { (cnt, lbl) ->
                        CompactChoicePill(
                            label = lbl,
                            selected = curCrossCount == cnt,
                            onClick = { saveCross(cnt, curCrossDistinct) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (curCrossCount >= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.cross_distinct_switch),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = curCrossDistinct,
                            onCheckedChange = { saveCross(curCrossCount, it) }
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        WaveShieldChip("l_shape", stringResource(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("t_shape", stringResource(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("square_2x2", stringResource(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("all_columns_ameliorate", stringResource(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)
                    ).forEach { chip ->
                        CompactChoicePill(
                            label = chip.label,
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) }
                        )
                    }
                }
            }
        }

        // ==================== 4. Path, Hazard & Step Controller ====================
        SectionHeader(stringResource(R.string.section_path_hazard_shield))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val burnOn = "zero_trail_burning" in activeIds
                fun upsertBurn(hurts: Int, len: Int) {
                    val packed = (hurts shl 8) or (len and 0xFF)
                    val lenLabel = if (len == 0) "Max" else "6"
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            id = "zero_trail_burning",
                            label = "${ctx.getString(R.string.ov_burn_path)}($hurts/$lenLabel)",
                            category = ShieldCategory.HAZARD_AND_PATH,
                            paramInt = packed
                        )
                    )
                }
                SwitchSettingRow(
                    title = stringResource(R.string.burn_path_title),
                    subtitle = stringResource(R.string.burn_path_sub),
                    checked = burnOn,
                    onCheckedChange = { enabled ->
                        if (enabled) upsertBurn(maxBurnHurts, burnTrailLen)
                        else ActiveStrategyStore.removeChip("zero_trail_burning")
                    }
                )
                if (burnOn) {
                    NumericStepperRow(
                        label = stringResource(R.string.burn_max_hurts),
                        valueText = "$maxBurnHurts ${stringResource(R.string.unit_times)}",
                        onMinus = {
                            maxBurnHurts = (maxBurnHurts - 1).coerceIn(0, 10)
                            upsertBurn(maxBurnHurts, burnTrailLen)
                        },
                        onPlus = {
                            maxBurnHurts = (maxBurnHurts + 1).coerceIn(0, 10)
                            upsertBurn(maxBurnHurts, burnTrailLen)
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactChoicePill(
                            label = stringResource(R.string.burn_len_max),
                            selected = burnTrailLen == 0,
                            onClick = {
                                burnTrailLen = 0
                                upsertBurn(maxBurnHurts, 0)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.burn_len_6),
                            selected = burnTrailLen == 6,
                            onClick = {
                                burnTrailLen = 6
                                upsertBurn(maxBurnHurts, 6)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val stepLbl = stringResource(R.string.shield_step_limit_fmt, stepLimitVal)
                    CompactChoicePill(
                        label = stepLbl,
                        selected = "step_limit" in activeIds,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("step_limit", stepLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                            )
                        }
                    )
                    listOf(
                        WaveShieldChip("trail_corrosion", stringResource(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
                        WaveShieldChip("step_hurt_minimize", stringResource(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("char_orb_terrain", stringResource(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("weijian_burst", stringResource(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH)
                    ).forEach { chip ->
                        CompactChoicePill(
                            label = chip.label,
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) }
                        )
                    }
                }
                if ("step_limit" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.shield_step_limit_step),
                        valueText = "≤ $stepLimitVal ${stringResource(R.string.unit_steps)}",
                        onMinus = {
                            stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "step_limit",
                                    ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal),
                                    ShieldCategory.HAZARD_AND_PATH,
                                    paramInt = stepLimitVal
                                )
                            )
                        },
                        onPlus = {
                            stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "step_limit",
                                    ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal),
                                    ShieldCategory.HAZARD_AND_PATH,
                                    paramInt = stepLimitVal
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ElementColorTogglePill(
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
private fun NumericStepperRow(
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

// ==================== Tab 3: Settings, Theme, Language, Speed & Benchmark ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    settings: AppSettings,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRunNativeBenchmark: () -> Unit
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Appearance, Theme Mode & Display Language (zh-CN / zh-TW / en Live Switch!)
        SectionHeader(stringResource(R.string.section_appearance))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
            }
        }

        // 2. Unified Thermal Speed Presets (1:1 synced with Floating Bar!)
        SectionHeader(stringResource(R.string.section_speed))
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.speed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${LocaleHelper.speedLabel(context, activeSpeed)} · ${settings.stepDurationMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (preset in SwipeSpeedPreset.entries) {
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
                                    text = "${preset.stepDurationMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Slider(
                    value = settings.stepDurationMs.toFloat().coerceIn(20f, 120f),
                    onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                    valueRange = 20f..120f
                )
            }
        }

        // 3. Solver Budget & Step Input (8..300)
        SectionHeader(stringResource(R.string.section_engine_steps))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    value = settings.beamWidth.toFloat(),
                    valueRange = 60f..400f,
                    onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = (v / 20f).roundToInt() * 20) } }
                )
                SliderSettingRow(
                    label = stringResource(R.string.slider_hold_delay),
                    valueText = "${settings.holdReplanDelayMs} ms",
                    value = settings.holdReplanDelayMs.toFloat(),
                    valueRange = 200f..800f,
                    onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = (v / 20f).roundToLong() * 20L) } }
                )
                SliderSettingRow(
                    label = stringResource(R.string.slider_countdown),
                    valueText = stringResource(R.string.unit_seconds, settings.countdownSeconds),
                    value = settings.countdownSeconds.toFloat(),
                    valueRange = 0f..3f,
                    steps = 2,
                    onValueChange = { v -> onUpdateSettings { it.copy(countdownSeconds = v.roundToInt()) } }
                )
            }
        }

        // 4. Mode & Control Switches
        SectionHeader(stringResource(R.string.section_modes))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_nudge_title),
                    subtitle = stringResource(R.string.mode_nudge_sub),
                    checked = settings.holdReplanNudge,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanNudge = v) } }
                )
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

        // 5. On-Device C++20 NDK Benchmark
        SectionHeader(stringResource(R.string.section_benchmark))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ==================== Shared Material 3 Building Blocks ====================
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
        FilledTonalButton(
            onClick = onAction,
            enabled = !granted || allowAlwaysClick,
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
private fun SwitchSettingRow(
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

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
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
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}
