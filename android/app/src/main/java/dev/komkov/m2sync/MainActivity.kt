package dev.komkov.m2sync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Медкарта необязательна — её отсутствие не считаем недостачей.
        val missing = (HealthWriter.permissions + HealthWriter.readPermissions) - granted
        if (missing.isEmpty()) LogBus.i(R.string.log_perm_count, granted.size, granted.size)
        else LogBus.e(R.string.log_missing_perms, missing.joinToString())
    }

    private val blePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) LogBus.e(R.string.log_missing_perms, denied.joinToString())
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
                    onPermissions = { requestAll() },
                )
            }
        }

        // При запуске: показать что есть локально, затем — если включено —
        // сразу пойти к велокомпьютеру и слить новые заезды.
        send(SyncService.ACTION_STATUS)
        if (Settings.autoSync.value && !autoSyncDone) {
            autoSyncDone = true
            send(SyncService.ACTION_SYNC)
        }
    }

    private fun requestAll() {
        blePermissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
        if (HealthWriter.available(this)) {
            // Медкарту просим только там, где она поддерживается: на остальных
            // устройствах такое разрешение неизвестно системе.
            val medical = if (HealthWriter.personalRecordsAvailable(this)) {
                HealthWriter.medicalPermissions
            } else {
                emptySet()
            }
            healthPermissions.launch(
                HealthWriter.permissions + HealthWriter.readPermissions + medical
            )
        } else {
            LogBus.e(R.string.log_hc_unavailable)
        }
    }

    private fun send(action: String) {
        startForegroundService(Intent(this, SyncService::class.java).setAction(action))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    onSync: () -> Unit,
    onInfo: () -> Unit,
    onVerify: () -> Unit,
    onPermissions: () -> Unit,
) {
    val ctx: Context = LocalContext.current
    val device by AppState.device.collectAsStateWithLifecycle()
    val rides by AppState.rides.collectAsStateWithLifecycle()
    val busy by AppState.busy.collectAsStateWithLifecycle()
    val action by AppState.action.collectAsStateWithLifecycle()
    val log by LogBus.lines.collectAsStateWithLifecycle()

    var selection by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selection.isNotEmpty()

    val autoSync by Settings.autoSync.collectAsStateWithLifecycle()
    val autoUpdate by Settings.autoUpdate.collectAsStateWithLifecycle()
    val update by AppState.update.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { UpdateChecker.checkIfDue(ctx) }

    if (profileOpen) ProfileDialog(onDismiss = { profileOpen = false })

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.selected_count, selection.size)
                        else stringResource(R.string.title_rides)
                    )
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            Sharing.shareAll(ctx, rides.filter { it.file in selection })
                        }) {
                            Icon(Icons.Rounded.Share, stringResource(R.string.cd_share))
                        }
                        IconButton(onClick = { selection = rides.map { it.file }.toSet() }) {
                            Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear_selection))
                        }
                    } else {
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
                                        onCheckedChange = { Settings.setAutoSync(ctx, it) },
                                    )
                                },
                                onClick = { Settings.setAutoSync(ctx, !autoSync) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_auto_update)) },
                                trailingIcon = {
                                    Switch(
                                        checked = autoUpdate,
                                        onCheckedChange = { Settings.setAutoUpdate(ctx, it) },
                                    )
                                },
                                onClick = { Settings.setAutoUpdate(ctx, !autoUpdate) },
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { inner ->
        RidesList(
            rides = rides,
            selection = selection,
            onToggle = { ride ->
                if (selecting) {
                    selection = if (ride.file in selection) selection - ride.file
                    else selection + ride.file
                }
            },
            onLongClick = { ride -> selection = selection + ride.file },
            onShare = { ride -> Sharing.shareRide(ctx, ride) },
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    update?.let {
                        UpdateCard(it, onDownload = {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(it.apkUrl ?: it.pageUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        })
                    }
                    DeviceCard(device, busy, action)
                    ActionsRow(onSync, onInfo, onVerify, enabled = !busy)
                    TotalsRow(rides)
                    LogCard(log)
                }
            },
            modifier = Modifier.padding(inner).padding(horizontal = 16.dp),
        )
    }
}
