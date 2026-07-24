package dev.komkov.m2sync

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val healthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
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
        send(SyncService.ACTION_STATUS)
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
            healthPermissions.launch(HealthWriter.permissions + HealthWriter.readPermissions)
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
