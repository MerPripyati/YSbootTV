package com.example.ysboottv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.IntentFilter
import android.os.IBinder


data class AppItem(
    val name: String,
    val packageName: String,
    val isTvPriority: Boolean
)

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private var showAllSystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isHomeLaunch = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val savedPkg = prefs.getString("target_pkg", null)

        if (isHomeLaunch && savedPkg != null) {
            val delaySec = prefs.getInt("delay_sec", 3)
            Handler(Looper.getMainLooper()).postDelayed({
                launchPackage(this, savedPkg)
                finish()
            }, delaySec * 1000L)
            return
        }

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(48, 36, 48, 36)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        val title = TextView(this).apply {
            text = "YSbootTV v$appVersion — Автозапуск ТВ (Холодный + Сон)"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(title)

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 0, 0, 24)
        }
        mainLayout.addView(statusText)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val btnPerm = createTvButton("1. Права поверх окон") {
            openOverlaySettings()
        }
        controlsRow.addView(btnPerm)

        val btnDelay = createTvButton("2. Задержка: ${prefs.getInt("delay_sec", 3)} сек") { btn ->
            val current = prefs.getInt("delay_sec", 3)
            val next = when (current) {
                0 -> 2
                2 -> 3
                3 -> 5
                5 -> 8
                8 -> 12
                else -> 0
            }
            prefs.edit().putInt("delay_sec", next).apply()
            btn.text = "2. Задержка: $next сек"
            updateStatus()
        }
        controlsRow.addView(btnDelay)

        val btnFilter = createTvButton("3. Все системные: ВЫКЛ") { btn ->
            showAllSystem = !showAllSystem
            btn.text = if (showAllSystem) "3. Все системные: ВКЛ" else "3. Все системные: ВЫКЛ"
            loadAppsList()
        }
        controlsRow.addView(btnFilter)

        val btnTest = createTvButton("4. Тест запуска") {
            val pkg = prefs.getString("target_pkg", null)
            if (pkg != null) {
                launchPackage(this, pkg)
            } else {
                Toast.makeText(this, "Сначала выберите ТВ из списка!", Toast.LENGTH_SHORT).show()
            }
        }
        controlsRow.addView(btnTest)

        mainLayout.addView(controlsRow)

        val listHeader = TextView(this).apply {
            text = "Выберите приложение (нажмите ОК на пульте):"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
        }
        mainLayout.addView(listHeader)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        rootScroll.addView(mainLayout)
        setContentView(rootScroll)

        updateStatus()
        loadAppsList()
        try {
            TvWatchService.start(this)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("target_name", "НЕ ВЫБРАНО")
        val savedPkg = prefs.getString("target_pkg", "—")
        val delay = prefs.getInt("delay_sec", 3)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val permStatus = if (hasOverlay) "ВЫДАНЫ" else "НЕТ ПРАВ (Нажмите кнопку 1)"
        statusText.text = "ТВ: $savedName ($savedPkg)\nЗадержка: $delay сек | Права: $permStatus | Сторож сна: АКТИВЕН"
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Окно скрыто. ADB: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadAppsList() {
        listContainer.removeAllViews()
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val currentPkg = prefs.getString("target_pkg", "")

        val keywords = listOf("tv", "livetv", "channel", "dvb", "tuner", "tele", "тв", "канал", "эфир")
        val appItems = mutableListOf<AppItem>()

        for (appInfo in packages) {
            if (appInfo.packageName == packageName) continue

            val label = pm.getApplicationLabel(appInfo).toString()
            val pkg = appInfo.packageName.lowercase()
            val lblLow = label.lowercase()

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val matchesTv = keywords.any { pkg.contains(it) || lblLow.contains(it) }
            val hasLaunchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) != null

            if (matchesTv || showAllSystem || hasLaunchIntent) {
                if (!showAllSystem && isSystem && !matchesTv) continue
                appItems.add(AppItem(label, appInfo.packageName, matchesTv))
            }
        }

        val sorted = appItems.sortedWith(
            compareByDescending<AppItem> { it.isTvPriority }.thenBy { it.name }
        )

        for (item in sorted) {
            val isSelected = item.packageName == currentPkg
            val prefix = when {
                isSelected -> "[ВЫБРАНО] "
                item.isTvPriority -> "[ТВ] "
                else -> ""
            }

            val btn = Button(this).apply {
                text = "$prefix${item.name} (${item.packageName})"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 16f
                setPadding(24, 18, 24, 18)
                setTextColor(Color.WHITE)
                setBackgroundColor(
                    if (isSelected) Color.parseColor("#1B5E20") else Color.parseColor("#263238")
                )
                isFocusable = true
                isFocusableInTouchMode = false

                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundColor(Color.parseColor("#0277BD"))
                    } else {
                        val active = item.packageName == prefs.getString("target_pkg", "")
                        v.setBackgroundColor(
                            if (active) Color.parseColor("#1B5E20") else Color.parseColor("#263238")
                        )
                    }
                }

                setOnClickListener {
                    prefs.edit()
                        .putString("target_pkg", item.packageName)
                        .putString("target_name", item.name)
                        .apply()
                    TvWatchService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Сохранено: ${item.name}", Toast.LENGTH_SHORT).show()
                    updateStatus()
                    loadAppsList()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            listContainer.addView(btn, params)
        }
    }

    private fun createTvButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(24, 16, 24, 16)
            isFocusable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(
                    if (hasFocus) Color.parseColor("#0277BD") else Color.parseColor("#37474F")
                )
            }
            setOnClickListener { onClick(this) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
        }
    }

    companion object {
        fun launchPackage(context: Context, pkgName: String) {
            val pm = context.packageManager

            try {
                val intent = pm.getLaunchIntentForPackage(pkgName)
                    ?: pm.getLeanbackLaunchIntentForPackage(pkgName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {}

            try {
                val queryIntent = Intent(Intent.ACTION_MAIN).apply { setPackage(pkgName) }
                val activities = pm.queryIntentActivities(queryIntent, 0)
                if (activities.isNotEmpty()) {
                    val info = activities[0].activityInfo
                    val compIntent = Intent(Intent.ACTION_MAIN).apply {
                        setClassName(info.packageName, info.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(compIntent)
                    return
                }
            } catch (_: Exception) {}
            try {
                val tvIntent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    setPackage(pkgName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(tvIntent)
                return
            } catch (_: Exception) {}
            try {
                val globalTv = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(globalTv)
            } catch (_: Exception) {}
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            TvWatchService.start(context)
            val prefs = context.getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
            val targetPkg = prefs.getString("target_pkg", null) ?: return
            val delaySec = prefs.getInt("delay_sec", 3)
            Handler(Looper.getMainLooper()).postDelayed({
                MainActivity.launchPackage(context, targetPkg)
            }, delaySec * 1000L)
        }
    }
}
class TvWatchService : Service() {
    private var screenReceiver: BroadcastReceiver? = null
    private var lastLaunchTime = 0L
    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) {
                    val now = System.currentTimeMillis()
                    if (now - lastLaunchTime < 5000L) return
                    lastLaunchTime = now
                    val prefs = context.getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
                    val targetPkg = prefs.getString("target_pkg", null) ?: return
                    val delaySec = prefs.getInt("delay_sec", 3)
                    Handler(Looper.getMainLooper()).postDelayed({
                        MainActivity.launchPackage(context, targetPkg)
                    }, delaySec * 1000L)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    override fun onDestroy() {
        screenReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun startForegroundNotification() {
        val channelId = "ysboot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "YSbootTV Фоновый контроль",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("YSbootTV активен")
            .setContentText("Автозапуск ТВ при включении экрана")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                101,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(101, notification)
        }
    }
    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, TvWatchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }
}

