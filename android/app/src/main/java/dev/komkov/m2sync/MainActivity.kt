package dev.komkov.m2sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** Автосинк — один раз на запуск процесса, а не на каждый поворот экрана. */
        private var autoSyncDone = false
    }

    private val healthPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            // Медкарта необязательна — её отсутствие не считаем недостачей.
            val missing = (HealthWriter.permissions + HealthWriter.readPermissions) - granted
            if (missing.isEmpty()) {
                LogBus.i(R.string.log_perm_count, granted.size, granted.size)
            } else {
                LogBus.e(R.string.log_missing_perms, missing.joinToString())
            }
        }

    private val blePermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                LogBus.e(R.string.log_missing_perms, denied.joinToString())
            } else if (Settings.autoSync.value && !autoSyncDone) {
                // Разрешения только что дали — синк, отложенный на старте, можно
                // наконец выполнить, не заставляя жать кнопку вручную.
                autoSyncDone = true
                send(SyncService.ACTION_SYNC)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogBus.init(this)
        enableEdgeToEdge()
        AppState.load(this)
        Settings.load(this)

        setContent {
            M2Theme {
                Screen(
                    onSync = { send(SyncService.ACTION_SYNC) },
                    onInfo = { send(SyncService.ACTION_INFO) },
                    onVerify = { send(SyncService.ACTION_VERIFY) },
                    onWeigh = { send(SyncService.ACTION_WEIGH) },
                    onPermissions = { requestAll() },
                )
            }
        }

        // При запуске: показать что есть локально, затем — если включено —
        // сразу пойти к велокомпьютеру и слить новые заезды. Без разрешений на
        // Bluetooth сначала спрашиваем их: синк всё равно был бы невозможен.
        send(SyncService.ACTION_STATUS)
        if (!SyncService.bleGranted(this)) {
            requestBle()
        } else if (Settings.autoSync.value && !autoSyncDone) {
            autoSyncDone = true
            send(SyncService.ACTION_SYNC)
        }
    }

    private fun requestBle() {
        blePermissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    private fun requestAll() {
        requestBle()
        if (HealthWriter.available(this)) {
            // Медкарту просим только там, где она поддерживается: на остальных
            // устройствах такое разрешение неизвестно системе.
            val medical =
                if (HealthWriter.personalRecordsAvailable(this)) {
                    HealthWriter.medicalPermissions
                } else {
                    emptySet()
                }
            healthPermissions.launch(
                HealthWriter.permissions + HealthWriter.readPermissions + medical,
            )
        } else {
            LogBus.e(R.string.log_hc_unavailable)
        }
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, SyncService::class.java).setAction(action))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Screen(
    onSync: () -> Unit,
    onInfo: () -> Unit,
    onVerify: () -> Unit,
    onWeigh: () -> Unit,
    onPermissions: () -> Unit,
) {
    val ctx: Context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val device by AppState.device.collectAsStateWithLifecycle()
    val rides by AppState.rides.collectAsStateWithLifecycle()
    val busy by AppState.busy.collectAsStateWithLifecycle()
    val action by AppState.action.collectAsStateWithLifecycle()
    val log by LogBus.lines.collectAsStateWithLifecycle()

    var selection by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selection.isNotEmpty()

    // Открытый заезд помним по имени файла, а не по объекту: список
    // пересобирается после каждого синка, и ссылка на старый протухла бы.
    var openRide by rememberSaveable { mutableStateOf<String?>(null) }
    rides.firstOrNull { it.file == openRide }?.let { ride ->
        RideDetailScreen(ride, onBack = { openRide = null })
        return
    }

    val autoSync by Settings.autoSync.collectAsStateWithLifecycle()
    val autoUpdate by Settings.autoUpdate.collectAsStateWithLifecycle()
    val mapLayer by Settings.mapLayer.collectAsStateWithLifecycle()
    val update by AppState.update.collectAsStateWithLifecycle()
    val updateProgress by AppState.updateProgress.collectAsStateWithLifecycle()
    val weight by AppState.weight.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Flexible top app bar: крупный заголовок наверху, сжимается при прокрутке.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(Unit) { UpdateChecker.checkIfDue(ctx) }

    // Синк идёт секундами, и телефон на это время обычно уже в кармане: короткий
    // отклик на финише говорит, что можно доставать, и отличается от отклика на
    // ошибке. Первое значение busy=false при запуске не считается концом работы.
    var wasBusy by remember { mutableStateOf(false) }
    var failuresBefore by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy) {
        if (busy) {
            wasBusy = true
            failuresBefore = LogBus.failures.value
        } else if (wasBusy) {
            wasBusy = false
            if (LogBus.failures.value > failuresBefore) haptics.failed() else haptics.done()
        }
    }

    if (profileOpen) ProfileDialog(onDismiss = { profileOpen = false })

    // Системное «назад» снимает выделение, а не закрывает приложение — с
    // predictive back это ещё и показывает, куда именно вернёшься.
    BackHandler(enabled = selecting) { selection = emptySet() }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                subtitle = {
                    val count = rides.size
                    if (!selecting && count > 0) Text(stringResource(R.string.subtitle_rides, count))
                },
                title = {
                    Text(
                        if (selecting) {
                            stringResource(R.string.selected_count, selection.size)
                        } else {
                            stringResource(R.string.title_rides)
                        },
                    )
                },
                actions = {
                    if (!selecting) {
                        IconButton(
                            onClick = { Sharing.shareAll(ctx, rides) },
                            enabled = rides.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Share, stringResource(R.string.cd_share_all))
                        }
                        IconButton(onClick = onPermissions) {
                            Icon(Icons.Rounded.Lock, stringResource(R.string.cd_permissions))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.cd_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_auto_sync)) },
                                trailingIcon = {
                                    Switch(
                                        checked = autoSync,
                                        onCheckedChange = {
                                            haptics.toggle(it)
                                            Settings.setAutoSync(ctx, it)
                                        },
                                    )
                                },
                                onClick = {
                                    haptics.toggle(!autoSync)
                                    Settings.setAutoSync(ctx, !autoSync)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_auto_update)) },
                                trailingIcon = {
                                    Switch(
                                        checked = autoUpdate,
                                        onCheckedChange = {
                                            haptics.toggle(it)
                                            Settings.setAutoUpdate(ctx, it)
                                        },
                                    )
                                },
                                onClick = {
                                    haptics.toggle(!autoUpdate)
                                    Settings.setAutoUpdate(ctx, !autoUpdate)
                                },
                            )
                            // Состояний три, поэтому не тумблер: пункт называет
                            // текущую подложку и по нажатию берёт следующую.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_map_layer)) },
                                trailingIcon = {
                                    Text(
                                        stringResource(mapLayer.label),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    haptics.tick()
                                    Settings.setMapLayer(ctx, mapLayer.next())
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_profile)) },
                                onClick = {
                                    menuOpen = false
                                    profileOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_check_now)) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { UpdateChecker.check(ctx) }
                                },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            // Панель у нижнего края: в режиме выделения действия должны быть
            // под большим пальцем, а не в шапке экрана.
            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    IconButton(onClick = {
                        Sharing.shareAll(ctx, rides.filter { it.file in selection })
                    }) {
                        Icon(Icons.Rounded.Share, stringResource(R.string.cd_share))
                    }
                    IconButton(onClick = {
                        haptics.tick()
                        selection = rides.map { it.file }.toSet()
                    }) {
                        Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all))
                    }
                    IconButton(onClick = {
                        haptics.toggle(false)
                        selection = emptySet()
                    }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_selection))
                    }
                }
            }
        },
    ) { inner ->
        RidesList(
            rides = rides,
            selection = selection,
            onToggle = { ride ->
                if (selecting) {
                    val add = ride.file !in selection
                    haptics.toggle(add)
                    selection = if (add) selection + ride.file else selection - ride.file
                } else {
                    openRide = ride.file
                }
            },
            onLongClick = { ride -> selection = selection + ride.file },
            onShare = { ride -> Sharing.shareRide(ctx, ride) },
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    update?.let {
                        UpdateCard(
                            update = it,
                            onDownload = { Updater.start(ctx, it) },
                            progress = updateProgress,
                        )
                    }
                    DeviceCard(device, busy, action)
                    ActionsRow(onSync, onInfo, onVerify, enabled = !busy)
                    WeightCard(weight, onWeigh, enabled = !busy)
                    TotalsRow(rides)
                    LogCard(log)
                }
            },
            modifier = Modifier.padding(inner).padding(horizontal = 16.dp),
            bottomInset = if (selecting) 96.dp else 0.dp,
        )
    }
}
