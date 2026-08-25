package com.timebank.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.timebank.app.data.ActivityState
import com.timebank.app.data.Economy
import com.timebank.app.service.TimeBankService
import com.timebank.app.util.formatMoney
import com.timebank.app.util.formatRate
import com.timebank.app.util.stateLabel

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current

    val balance by Economy.balance.collectAsState()
    val activity by Economy.activity.collectAsState()
    val rate by Economy.ratePerMin.collectAsState()
    val running by Economy.serviceRunning.collectAsState()
    val locked by Economy.locked.collectAsState()
    val pkg by Economy.currentPackage.collectAsState()
    val privateSurcharge by Economy.privateSurchargeActive.collectAsState()
    val happyHour by Economy.happyHourActive.collectAsState()
    val surge by Economy.surgeActive.collectAsState()

    val resume = rememberResumeTick()
    val hasUsage = remember(resume) { hasUsageAccess(ctx) }
    val hasMedia = remember(resume) { isNotificationListenerEnabled(ctx) }
    val ignoringBattery = remember(resume) { isIgnoringBatteryOptimizations(ctx) }
    val canOverlay = remember(resume) { canDrawOverlays(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Balance", style = MaterialTheme.typography.titleMedium)
        Text(
            text = formatMoney(balance),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))
        val rateColor = if (rate >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
        Text(
            text = "${formatRate(rate)} / min",
            color = rateColor,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(4.dp))
        val label = stateLabel(activity) +
            if (activity == ActivityState.APP && !pkg.isNullOrEmpty()) "\n$pkg" else ""
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )

        if (happyHour) {
            Text(
                text = "\uD83C\uDF7A  happy hour — apps are cheap",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (surge) {
            Text(
                text = "\u26A1  surge — apps cost more right now",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (privateSurcharge) {
            Text(
                text = "🕵\u200d♂\uFE0F  incognito surcharge applied",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (running) {
                    TimeBankService.stop(ctx)
                } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            ctx, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    TimeBankService.start(ctx)
                }
            },
            enabled = running || hasUsage,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Text(if (running) "Stop earning" else "Start earning")
        }


        if (locked) {
            Spacer(Modifier.height(16.dp))
            Text(
                "🔒 Locked — you're out of money",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Setup",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )

        PermissionCard(
            title = "Usage access (required)",
            desc = "Detects which app is open so it can charge the per-minute cost.",
            granted = hasUsage
        ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        PermissionCard(
            title = "Notification access — media",
            desc = "Detects YouTube / music playback to apply the media rate.",
            granted = hasMedia
        ) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        PermissionCard(
            title = "Ignore battery optimization",
            desc = "Keeps earning reliably while the screen is off.",
            granted = ignoringBattery
        ) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        }

        PermissionCard(
            title = "Display over other apps",
            desc = "Lets TimeBank lock the screen with an overlay when you hit \$0.",
            granted = canOverlay
        ) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        }
    }
}
