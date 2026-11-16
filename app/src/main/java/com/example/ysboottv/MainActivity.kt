package com.example.ysboottv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

data class AppItem(
    val name: String,
    val packageName: String,
    val isTvPriority: Boolean
)

data class InputOption(
    val id: String,
    val label: String
)

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnSource: Button
    private var showAllSystem = false
    private lateinit var listHeader: TextView
    private var showInputsMode = false
    private var availableInputs: List<InputOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isHomeLaunch = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)

        if (isHomeLaunch && hasConfiguredTarget(prefs)) {
            val delaySec = prefs.getInt("delay_sec", 3)
            Handler(Looper.getMainLooper()).postDelayed({
                launchConfiguredTarget(this)
                finish()
            }, delaySec * 1000L)
            return
        }

        availableInputs = loadAvailableTvInputs()

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
            requestBatteryExemption()
            openOverlaySettings()
        }.apply {
            setOnLongClickListener {
                openAppDetailsSettings()
                true
            }
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

        btnSource = createTvButton("4. Видеовыходы") { btn ->
            showInputsMode = !showInputsMode
            if (showInputsMode) {
                btn.text = "4. К приложениям"
                listHeader.text = "Выберите видеовыход (нажмите ОК на пульте):"
                loadInputsList()
            } else {
                btn.text = "4. Видеовыходы"
                listHeader.text = "Выберите приложение (нажмите ОК на пульте):"
                loadAppsList()
            }
        }
        controlsRow.addView(btnSource)


        val btnTest = createTvButton("5. Тест запуска") {
            if (hasConfiguredTarget(prefs)) {
                launchConfiguredTarget(this)
            } else {
                Toast.makeText(this, "Сначала выберите ТВ из списка или HDMI в пункте 4!", Toast.LENGTH_SHORT).show()
            }
        }
        controlsRow.addView(btnTest)

        mainLayout.addView(controlsRow)

        listHeader = TextView(this).apply {
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

    private fun loadAvailableTvInputs(): List<InputOption> {
        val result = mutableListOf(InputOption("", "ВЫКЛ"))
        try {
            val tvManager = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val inputs = tvManager?.tvInputList ?: emptyList()
            for (info in inputs) {
                if (info.isPassthroughInput || info.type != TvInputInfo.TYPE_TUNER) {
                    val label = info.loadLabel(this)?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.id.substringAfterLast("/")
                    result.add(InputOption(info.id, label))
                }
            }
        } catch (_: Exception) {}


        return result
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val inputId = prefs.getString("target_input_id", "") ?: ""
        val inputLabel = prefs.getString("target_input_label", "ВЫКЛ") ?: "ВЫКЛ"
        val savedName = prefs.getString("target_name", "НЕ ВЫБРАНО")
        val savedPkg = prefs.getString("target_pkg", "—")
        val delay = prefs.getInt("delay_sec", 3)

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else true

        val permStatus = when {
            hasOverlay && batteryIgnored -> "ВЫДАНЫ (Полные)"
            hasOverlay -> "ВЫДАНЫ (Удерживайте 1 для настроек)"
            else -> "НЕТ ОВЕРЛЕЯ (Резерв FullScreen / Удерживайте 1)"
        }

        val targetInfo = if (inputId.isNotEmpty()) {
            "Видеовыход: $inputLabel (Резерв ТВ: $savedName)"
        } else {
            "ТВ: $savedName ($savedPkg)"
        }

        statusText.text = "$targetInfo\nЗадержка: $delay сек | Права: $permStatus | Сторож сна: АКТИВЕН"
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {}
        }
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

    private fun loadInputsList() {
        listContainer.removeAllViews()
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val currentInputId = prefs.getString("target_input_id", "") ?: ""
        val currentPkg = prefs.getString("target_pkg", null)

        if (currentPkg != null || currentInputId.isNotEmpty()) {
            val clearBtn = Button(this).apply {
                text = "✕ Сбросить выбор (Отключить автозапуск)"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 16f
                setPadding(24, 18, 24, 18)
                setTextColor(Color.parseColor("#FFCDD2"))
                setBackgroundColor(Color.parseColor("#4A2C2A"))
                isFocusable = true
                isFocusableInTouchMode = false
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        if (hasFocus) Color.parseColor("#6E3B37") else Color.parseColor("#4A2C2A")
                    )
                }
                setOnClickListener { clearSelection(prefs) }
            }
            val clearParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 14) }
            listContainer.addView(clearBtn, clearParams)
        }

        val inputs = loadAvailableTvInputs().filter { it.id.isNotEmpty() }
        if (inputs.isEmpty()) {
            val emptyInfo = TextView(this).apply {
                text = "Аппаратные видеовыходы (HDMI / AV) на этом устройстве не найдены."
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(16, 24, 16, 24)
            }
            listContainer.addView(emptyInfo)
            return
        }

        for (item in inputs) {
            val isSelected = item.id == currentInputId
            val prefix = if (isSelected) "[ВЫБРАНО] " else "[ВХОД] "

            val btn = Button(this).apply {
                text = "$prefix${item.label} (${item.id})"
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
                        val active = item.id == prefs.getString("target_input_id", "")
                        v.setBackgroundColor(
                            if (active) Color.parseColor("#1B5E20") else Color.parseColor("#263238")
                        )
                    }
                }

                setOnClickListener {
                    val activeId = prefs.getString("target_input_id", "") ?: ""
                    if (activeId == item.id) {
                        prefs.edit()
                            .putString("target_input_id", "")
                            .putString("target_input_label", "ВЫКЛ")
                            .apply()
                        Toast.makeText(this@MainActivity, "Выбор снят: ${item.label}", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit()
                            .remove("target_pkg")
                            .remove("target_name")
                            .putString("target_input_id", item.id)
                            .putString("target_input_label", item.label)
                            .apply()
                        TvWatchService.start(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Сохранено: ${item.label}", Toast.LENGTH_SHORT).show()
                    }
                    updateStatus()
                    loadInputsList()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            listContainer.addView(btn, params)
        }
    }

    private fun loadAppsList() {
        listContainer.removeAllViews()
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        val currentPkg = prefs.getString("target_pkg", null)
        val currentInputId = prefs.getString("target_input_id", "") ?: ""

        if (currentPkg != null || currentInputId.isNotEmpty()) {
            val clearBtn = Button(this).apply {
                text = "✕ Сбросить выбор (Отключить автозапуск)"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 16f
                setPadding(24, 18, 24, 18)
                setTextColor(Color.parseColor("#FFCDD2"))
                setBackgroundColor(Color.parseColor("#4A2C2A"))
                isFocusable = true
                isFocusableInTouchMode = false

                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(
                        if (hasFocus) Color.parseColor("#6E3B37") else Color.parseColor("#4A2C2A")
                    )
                }

                setOnClickListener {
                    clearSelection(prefs)
                }
            }
            val clearParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 14)
            }
            listContainer.addView(clearBtn, clearParams)
        }

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
                        val active = item.packageName == prefs.getString("target_pkg", null)
                        v.setBackgroundColor(
                            if (active) Color.parseColor("#1B5E20") else Color.parseColor("#263238")
                        )
                    }
                }

                setOnClickListener {
                    val activePkg = prefs.getString("target_pkg", null)
                    if (activePkg == item.packageName) {
                        prefs.edit()
                            .remove("target_pkg")
                            .remove("target_name")
                            .apply()
                        Toast.makeText(this@MainActivity, "Выбор снят: ${item.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit()
                            .putString("target_pkg", item.packageName)
                            .putString("target_name", item.name)
                            .putString("target_input_id", "")
                            .putString("target_input_label", "ВЫКЛ")
                            .apply()
                        btnSource.text = "4. Видеовыходы"
                        TvWatchService.start(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Сохранено: ${item.name}", Toast.LENGTH_SHORT).show()
                    }
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

    private fun clearSelection(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .remove("target_pkg")
            .remove("target_name")
            .putString("target_input_id", "")
            .putString("target_input_label", "ВЫКЛ")
            .apply()
        btnSource.text = if (showInputsMode) "4. К приложениям" else "4. Видеовыходы"
        Toast.makeText(this, "Автозапуск отключен (ничего не выбрано)", Toast.LENGTH_SHORT).show()
        updateStatus()
        if (showInputsMode) loadInputsList() else loadAppsList()
    }

    private fun createTvButton(label: String, onClick: (Button) -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            maxLines = 1
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(18, 16, 18, 16)
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
                setMargins(0, 0, 12, 0)
            }
        }
    }
    companion object {
        fun hasConfiguredTarget(prefs: android.content.SharedPreferences): Boolean {
            val pkg = prefs.getString("target_pkg", null)
            val inputId = prefs.getString("target_input_id", "") ?: ""
            return !pkg.isNullOrEmpty() || inputId.isNotEmpty()
        }

        fun launchConfiguredTarget(context: Context) {
            val prefs = context.getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
            val inputId = prefs.getString("target_input_id", "") ?: ""
            val pkgName = prefs.getString("target_pkg", null)

            if (inputId.isNotEmpty()) {
                launchTvInput(context, inputId, pkgName)
            } else if (!pkgName.isNullOrEmpty()) {
                launchPackage(context, pkgName)
            }
        }

        fun launchTvInput(context: Context, inputId: String, fallbackPkg: String?) {
            try {
                val uri = TvContract.buildChannelUriForPassthroughInput(inputId)
                val inputIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (startActivitySafe(context, inputIntent)) return
            } catch (_: Exception) {}

            try {
                val tvIntent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    putExtra(TvInputInfo.EXTRA_INPUT_ID, inputId)
                    if (!fallbackPkg.isNullOrEmpty()) setPackage(fallbackPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (startActivitySafe(context, tvIntent)) return
            } catch (_: Exception) {}

            try {
                val globalTv = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    putExtra(TvInputInfo.EXTRA_INPUT_ID, inputId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (startActivitySafe(context, globalTv)) return
            } catch (_: Exception) {}

            if (!fallbackPkg.isNullOrEmpty()) {
                launchPackage(context, fallbackPkg)
            }
        }

        fun launchPackage(context: Context, pkgName: String) {
            val pm = context.packageManager

            try {
                val intent = pm.getLaunchIntentForPackage(pkgName)
                    ?: pm.getLeanbackLaunchIntentForPackage(pkgName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (startActivitySafe(context, intent)) return
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
                    if (startActivitySafe(context, compIntent)) return
                }
            } catch (_: Exception) {}

            try {
                val tvIntent = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    setPackage(pkgName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (startActivitySafe(context, tvIntent)) return
            } catch (_: Exception) {}

            try {
                val globalTv = Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivitySafe(context, globalTv)
            } catch (_: Exception) {}
        }
        private fun startActivitySafe(context: Context, intent: Intent): Boolean {
            return try {
                val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true

                if (canOverlay || context is Activity) {
                    context.startActivity(intent)
                    true
                } else {
                    try {
                        context.startActivity(intent)
                        true
                    } catch (_: Exception) {
                        launchViaFullScreenNotification(context, intent)
                        true
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        @SuppressLint("MissingPermission")
        private fun launchViaFullScreenNotification(context: Context, targetIntent: Intent) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "ysboot_wakeup_channel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        channelId,
                        "YSbootTV Запуск ТВ",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                    nm.createNotificationChannel(channel)
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val fullScreenPi = PendingIntent.getActivity(context, 888, targetIntent, flags)

                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, channelId)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }

                val notif = builder
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("YSbootTV")
                    .setContentText("Запуск ТВ...")
                    .setFullScreenIntent(fullScreenPi, true)
                    .setAutoCancel(true)
                    .build()

                nm.notify(888, notif)
                Handler(Looper.getMainLooper()).postDelayed({
                    try { nm.cancel(888) } catch (_: Exception) {}
                }, 2000L)
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
            if (!MainActivity.hasConfiguredTarget(prefs)) return
            val delaySec = prefs.getInt("delay_sec", 3)
            Handler(Looper.getMainLooper()).postDelayed({
                MainActivity.launchConfiguredTarget(context)
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
                    if (!MainActivity.hasConfiguredTarget(prefs)) return
                    val delaySec = prefs.getInt("delay_sec", 3)
                    Handler(Looper.getMainLooper()).postDelayed({
                        MainActivity.launchConfiguredTarget(context)
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
