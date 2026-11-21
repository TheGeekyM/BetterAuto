package com.simoneluconi.betterauto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.smarthome.PhoneTextField
import com.simoneluconi.betterauto.ui.theme.LocalCarColors

/**
 * Phone-only (typing): the driver's name and nicknames, so the assistant greets and answers them personally.
 * Kept on the phone only; empty fields: no name used.
 */
@Composable
internal fun DriverNameSettings() {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val settings = remember { LauncherSettings.get(context) }
    val name by settings.driverName.collectAsStateWithLifecycle()
    val nicknames by settings.nicknames.collectAsStateWithLifecycle()
    var typedName by rememberSaveable { mutableStateOf(name) }
    var typedNicknames by rememberSaveable { mutableStateOf(nicknames) }
    var saved by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CarLabel(stringResource(R.string.driver_name_label), 15.sp, colors.text2, weight = FontWeight.Bold)
        PhoneTextField(typedName, { typedName = it; saved = false }, stringResource(R.string.driver_name_hint), keyboard = KeyboardType.Text)
        CarLabel(stringResource(R.string.driver_nicknames_label), 15.sp, colors.text2, weight = FontWeight.Bold)
        PhoneTextField(typedNicknames, { typedNicknames = it; saved = false }, stringResource(R.string.driver_nicknames_hint), keyboard = KeyboardType.Text)
        if (saved) CarLabel(stringResource(R.string.driver_name_saved), 14.sp, colors.text2)
    }
    PillButton(stringResource(R.string.driver_name_save), primary = true) {
        settings.setDriverName(typedName, typedNicknames)
        saved = true
        // Never the name itself: it is personal
        EventLog.log("Voice: driver name ${if (typedName.isBlank()) "cleared" else "set"}")
    }
}

/** "Set" or "Not set", never the name (the settings home may be seen by others). */
@Composable
internal fun driverNameSummary(): String {
    val context = LocalContext.current
    val name by remember { LauncherSettings.get(context) }.driverName.collectAsStateWithLifecycle()
    return stringResource(if (name.isBlank()) R.string.driver_name_not_set else R.string.driver_name_set)
}
