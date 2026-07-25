package dev.komkov.m2sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Экран обоснования доступа — его требует Health Connect.
 *
 * Раньше здесь был голый TextView мимо темы приложения. Человек попадает сюда
 * из системного диалога разрешений, и экран должен выглядеть частью того же
 * приложения, а не служебной заглушкой.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { M2Theme { RationaleScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    Scaffold(Modifier.fillMaxSize()) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Rounded.HealthAndSafety,
                null,
                Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                stringResource(R.string.rationale),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.rationale_what),
                style = MaterialTheme.typography.titleSmall,
            )
            // Перечисляем ровно то, что уходит в Health Connect: обещание в
            // тексте выше стоит подкрепить списком.
            DataRow(Icons.Rounded.DirectionsBike, stringResource(R.string.rationale_ride))
            DataRow(Icons.Rounded.Favorite, stringResource(R.string.rationale_heart))
            DataRow(Icons.Rounded.Route, stringResource(R.string.rationale_route))
            DataRow(Icons.Rounded.Bolt, stringResource(R.string.rationale_calories))
            DataRow(Icons.Rounded.MonitorWeight, stringResource(R.string.rationale_weight))
            Spacer(Modifier.size(8.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rationale_close))
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DataRow(icon: ImageVector, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
