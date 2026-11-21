package com.simoneluconi.betterauto.assist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.PillButton
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass

/** Where the car is parked, on the phone: the notification at the end of a drive and the card in the app. */
internal object ParkedCarNotice {
    private const val CHANNEL_ID = "parked_car"
    private const val NOTIFICATION_ID = 7311

    /** Walking directions to the car: Google Maps when installed, else the same link in the browser. */
    fun walkIntent(car: ParkedCar): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${car.latitude},${car.longitude}&travelmode=walking"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "On Via Roma, since 18:40", or only the time when the road had no name; [notice] adds "tap to walk there". */
    fun text(context: Context, car: ParkedCar, notice: Boolean): String {
        val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(car.at)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        val time = DateUtils.formatDateTime(context, car.at, flags)
        return when {
            car.road != null -> context.getString(if (notice) R.string.assist_parked_notify_road else R.string.assist_parked_card_road, car.road, time)
            else -> context.getString(if (notice) R.string.assist_parked_notify_time else R.string.assist_parked_card_time, time)
        }
    }

    /** A quiet notification (no sound, no pop-up): the car's place, a tap away from walking there. */
    fun post(context: Context, car: ParkedCar) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            EventLog.log("Assist: no notification permission for the parked car")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.assist_parked_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, walkIntent(car), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.assist_parked_title))
            .setContentText(text(context, car, notice = true))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        EventLog.log("Assist: parked car notification posted")
    }

    /** The car is back in use: its old place is no news. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** The phone app's card: where the car was left, with walking directions; nothing while the setting is off. */
@Composable
fun ParkedCarCard() {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    LaunchedEffect(Unit) { AssistStore.init(context) }
    val enabled by LauncherSettings.get(context).assistParked.collectAsStateWithLifecycle()
    val car by AssistStore.parked.collectAsStateWithLifecycle()
    if (!enabled) return
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(if (car == null) colors.surface2 else colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                CarIcon(CarIcons.Car, if (car == null) colors.text2 else colors.onAccent, Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CarLabel(stringResource(R.string.assist_parked_card_title), 18.sp, colors.text, weight = FontWeight.Bold)
                CarLabel(car?.let { ParkedCarNotice.text(context, it, notice = false) } ?: stringResource(R.string.assist_parked_card_none), 15.sp, colors.text2, maxLines = 3)
            }
        }
        car?.let { parked ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.assist_parked_walk), primary = true, modifier = Modifier.weight(1f)) {
                    runCatching { context.startActivity(ParkedCarNotice.walkIntent(parked)) }
                        .onFailure { EventLog.log("Assist: no app to walk to the car") }
                }
                PillButton(stringResource(R.string.assist_parked_forget), primary = false) {
                    AssistStore.setParked(context, null)
                    ParkedCarNotice.cancel(context)
                }
            }
        }
    }
}
