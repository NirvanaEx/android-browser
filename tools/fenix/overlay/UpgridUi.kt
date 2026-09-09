package org.mozilla.fenix.upgrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.feature.addons.Addon
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components

@Composable
fun UpgridHomepage(
    privateMode: Boolean,
    onSearch: () -> Unit,
    onPrivateMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Upgrid", fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (privateMode) Text(stringResource(R.string.upgrid_private_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                onClick = onSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(stringResource(R.string.upgrid_home_hint), Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onPrivateMode) {
                Text(stringResource(if (privateMode) R.string.upgrid_normal_tabs else R.string.upgrid_private_tabs))
            }
        }
    }
}

@Composable
fun UpgridAdblockSwitch() {
    val addons = LocalContext.current.components.addonManager
    var addon by remember { mutableStateOf<Addon?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(addons) { addon = runCatching { addons.getAddonByID(UpgridAdblock.ID) }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.upgrid_adblock), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = addon?.isEnabled() == true, enabled = addon != null && !busy, onCheckedChange = { enabled ->
            val current = addon ?: return@Switch
            busy = true
            val done: (Addon) -> Unit = { addon = it; busy = false }
            val failed: (Throwable) -> Unit = { busy = false }
            if (enabled) addons.enableAddon(current, onSuccess = done, onError = failed)
            else addons.disableAddon(current, onSuccess = done, onError = failed)
        })
    }
}
