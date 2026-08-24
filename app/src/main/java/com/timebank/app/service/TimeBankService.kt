package com.timebank.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.timebank.app.MainActivity
import com.timebank.app.data.ActivityState
import com.timebank.app.data.AppGraph
import com.timebank.app.data.Economy
import com.timebank.app.util.formatCompactMoney
import com.timebank.app.util.formatMoney
import com.timebank.app.util.formatRate
import com.timebank.app.util.stateLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The heart of the app. A foreground service that holds a partial wake lock so
 * it keeps ticking while the screen is off, and every few seconds updates the
 * balance based on what the phone is doing. When the balance hits $0 while an
 * app is open it raises a full-screen lock overlay.
 */
class TimeBankService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var powerManager: PowerManager
    private lateinit var fgMonitor: ForegroundAppMonitor
    private lateinit var lockOverlay: LockOverlay
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null

    private var lastTickMs = 0L
    private var persistCounter = 0
    private var launcherPackage: String? = null

    /** Persist immediately whenever the screen turns off/on (a natural checkpoint). */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch(Dispatchers.IO) {
                AppGraph.settings.saveBalance(Economy.balance.value)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        fgMonitor = ForegroundAppMonitor(this)
        lockOverlay = LockOverlay(this)
        launcherPackage = resolveLauncherPackage()
        createChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startForegroundInternal()
        acquireWakeLock()
        Economy.serviceRunning.value = true
        lastTickMs = SystemClock.elapsedRealtime()
        startTicking()
        return START_STICKY
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                tick()
                updateNotification()
                delay(TICK_MS)
            }
        }
    }

    /** One accounting step. Uses elapsed-time deltas so accuracy is independent of TICK_MS. */
    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val deltaMin = (now - lastTickMs) / 60_000.0
        lastTickMs = now
        if (deltaMin <= 0) return

        val cfg = Economy.config.value
        val screenOn = powerManager.isInteractive
        val mediaPlaying = MediaMonitor.isMediaPlaying(this)
        val fgPkg = if (screenOn) fgMonitor.currentForegroundPackage() else null
        Economy.currentPackage.value = fgPkg

        // Only evaluated when the screen is on and no media is playing.
        val isNeutral =
            fgPkg == null || fgPkg == packageName || fgPkg == launcherPackage

        // Private browsing costs extra, but only for the app actually in the foreground:
        // a backgrounded browser with incognito tabs open shouldn't tax an unrelated app.
        val privateNow =
            fgPkg != null && fgPkg in Economy.privatePackages.value &&
                cfg.privateSurchargePerMin > 0.0

        val (state, ratePerMin) = when {
            mediaPlaying ->
                ActivityState.MEDIA to cfg.mediaRatePerMin * cfg.earnMultiplier
            !screenOn ->
                ActivityState.SCREEN_OFF to cfg.offRatePerMin * cfg.earnMultiplier
            isNeutral ->
                ActivityState.NEUTRAL to 0.0
            else -> {
                val surcharge = if (privateNow) cfg.privateSurchargePerMin else 0.0
                ActivityState.APP to -(cfg.costFor(fgPkg) + surcharge)
            }
        }

        // Only meaningful while actually being charged for an app.
        Economy.privateSurchargeActive.value = privateNow && state == ActivityState.APP
        Economy.activity.value = state
        Economy.ratePerMin.value = ratePerMin

        val newBalance = max(0.0, Economy.balance.value + ratePerMin * deltaMin)
        Economy.balance.value = newBalance

        // Lock the phone out of apps when broke.
        val broke = cfg.lockWhenBroke && newBalance <= 0.0 && state == ActivityState.APP
        Economy.locked.value = broke
        if (broke) lockOverlay.show() else lockOverlay.hide()

        if (++persistCounter >= PERSIST_EVERY_TICKS) {
            persistCounter = 0
            scope.launch(Dispatchers.IO) {
                AppGraph.settings.saveBalance(newBalance)
            }
        }
    }

    private fun resolveLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

    // --- wake lock -----------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "TimeBank::earn"
            ).apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // --- notification --------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "TimeBank", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows your live balance and rate." }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundInternal() {
        val type =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TimeBankService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val rate = Economy.ratePerMin.value
        val balance = Economy.balance.value
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The balance itself is the status-bar icon, so it's visible without the shade.
            .setSmallIcon(BalanceIcon.forText(formatCompactMoney(balance)))
            .setContentTitle("Balance: " + formatMoney(balance))
            .setContentText(stateLabel(Economy.activity.value) + "  •  " + formatRate(rate) + "/min")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    // --- lifecycle -----------------------------------------------------------

    private fun stopEverything() {
        scope.launch(Dispatchers.IO) {
            AppGraph.settings.saveBalance(Economy.balance.value)
        }
        tickJob?.cancel()
        releaseWakeLock()
        lockOverlay.hide()
        Economy.serviceRunning.value = false
        Economy.locked.value = false
        Economy.activity.value = ActivityState.STOPPED
        Economy.ratePerMin.value = 0.0
        Economy.privateSurchargeActive.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        releaseWakeLock()
        lockOverlay.hide()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        Economy.serviceRunning.value = false
        Economy.locked.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "timebank_service"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.timebank.app.action.STOP"
        private const val TICK_MS = 3_000L
        private const val PERSIST_EVERY_TICKS = 4 // persist ~every 12s

        fun start(context: Context) {
            val i = Intent(context, TimeBankService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TimeBankService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
