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
    private lateinit var btnPerm: Button
    private lateinit var btnSource: Button
    private var showAllSystem = false

    private var pickingHomeLauncher = false
    private lateinit var listHeader: TextView
    private var currentTab = 0 // 0 = Приложения, 1 = Видеовыходы, 2 = Права и обход
    private var availableInputs: List<InputOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        handleHomeLaunch(intent)


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

        btnPerm = createTvButton("1. Права и обход") {
            pickingHomeLauncher = false
            switchTab(2)
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

        val btnAutoStart = createTvButton("3. Автозапуск") {
            pickingHomeLauncher = false
            switchTab(0)
        }
        controlsRow.addView(btnAutoStart)

        btnSource = createTvButton("4. Видеовыходы") {
            pickingHomeLauncher = false
            switchTab(1)
        }
        controlsRow.addView(btnSource)

        val btnTest = createTvButton("5. Тест запуска") {
            if (hasConfiguredTarget(prefs)) {
                launchConfiguredTarget(this)
            } else {
                Toast.makeText(this, "Сначала выберите ТВ в п.3 или HDMI в п.4!", Toast.LENGTH_SHORT).show()
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
        val isFirstRun = !prefs.getBoolean("first_setup_shown", false)
        if (isFirstRun) {
            prefs.edit().putBoolean("first_setup_shown", true).apply()
            switchTab(2)
        } else {
            switchTab(0)
        }

        try {
            TvWatchService.start(this)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeLaunch(intent)
    }

    private fun handleHomeLaunch(launchIntent: Intent?): Boolean {
        val isHomeLaunch = launchIntent?.hasCategory(Intent.CATEGORY_HOME) == true
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        if (!isHomeLaunch) return false

        // 1. Проверяем, был ли это первый запуск после включения/перезагрузки телевизора
        if (!wasHomeBootHandled && hasConfiguredTarget(prefs)) {
            wasHomeBootHandled = true
            val delaySec = prefs.getInt("delay_sec", 3)
            Handler(Looper.getMainLooper()).postDelayed({
                launchConfiguredTarget(this)
            }, delaySec * 1000L)
            return false // Показываем интерфейс YSbootTV во время задержки, чтобы не было пустого экрана
        }

        // 2. Повторное нажатие кнопки «Домой» -> переходим на родной рабочий стол
        val customHomePkg = prefs.getString("home_launcher_pkg", null)
        if (!customHomePkg.isNullOrEmpty()) {
            try {
                val customIntent = packageManager.getLaunchIntentForPackage(customHomePkg)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(customHomePkg)
                if (customIntent != null) {
                    customIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(customIntent)
                    return false
                }
            } catch (_: Exception) {}
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            PackageManager.MATCH_DISABLED_COMPONENTS
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfos = packageManager.queryIntentActivities(homeIntent, flags)

        val realLaunchers = resolveInfos.filter {
            val pkg = it.activityInfo.packageName
            pkg != packageName && pkg != "com.android.settings"
        }

        for (info in realLaunchers) {
            val stockPkg = info.activityInfo.packageName
            val stockCls = info.activityInfo.name
            try {
                val directNoCat = Intent(Intent.ACTION_MAIN).apply {
                    setClassName(stockPkg, stockCls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(directNoCat)
                return false
            } catch (_: Exception) {}
        }

        val knownLaunchers = listOf(
            "com.android.ld.appstore",
            "com.android.ld.launcher",
            "com.android.launcher3",
            "com.android.launcher",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "ru.sberdevices.starlauncher",
            "com.yandex.tv.home",
            "com.mitv.tvhome",
            "com.xiaomi.mitv.tvhome",
            "com.tcl.cyberui",
            "com.tcl.tvlauncher",
            "com.hisense.tv.launcher",
            "org.droidtv.nettv",
            "com.mediatek.wwtv.tvcenter"
        )
        for (pkg in knownLaunchers) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return false
                }
            } catch (_: Exception) {}
        }

        return false
    }





    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            updateStatus()
            if (currentTab == 2) loadPermissionsList()
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        btnPerm.text = if (pickingHomeLauncher) {
            "1. Назад к правам"
        } else {
            "1. Права и обход"
        }
        when (tab) {
            0 -> {
                pickingHomeLauncher = false
                listHeader.text = "Автозапуск: выберите приложение (нажмите ОК):"
                loadAppsList()
            }
            1 -> {
                listHeader.text = "Выберите видеовыход (нажмите ОК на пульте):"
                loadInputsList()
            }
            2 -> {
                listHeader.text = "Настройка прав и обхода блокировок (нажмите ОК):"
                loadPermissionsList()
            }
        }
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
            hasOverlay -> "ВЫДАНЫ (Откройте п.1 для работы в фоне)"
            else -> "НЕТ ОВЕРЛЕЯ (Резерв FullScreen / Откройте п.1)"
        }

        val targetInfo = if (inputId.isNotEmpty()) {
            "Видеовыход: $inputLabel (Резерв ТВ: $savedName)"
        } else {
            "ТВ: $savedName ($savedPkg)"
        }

        statusText.text = "$targetInfo\nЗадержка: $delay сек | Права: $permStatus | Сторож сна: АКТИВЕН"
    }

    private fun loadPermissionsList() {
        listContainer.removeAllViews()

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } else true

        val notifEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.areNotificationsEnabled() != false
        } else true

        val isDefaultLauncher = try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val res = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            res?.activityInfo?.packageName == packageName
        } catch (_: Exception) { false }

        val items = listOf(
            Triple(
                if (hasOverlay) "1. Поверх других окон (Оверлей) [ВКЛ]" else "1. Поверх других окон (Оверлей) [ВЫКЛ]",
                hasOverlay
            ) { openOverlaySettings() },
            Triple(
                if (batteryIgnored) "2. Работа в фоне / Батарея [ВКЛ]" else "2. Работа в фоне / Батарея [ВЫКЛ]",
                batteryIgnored
            ) { requestBatteryExemption() },
            Triple(
                if (notifEnabled) "3. Уведомления (для Android 13–14) [ВКЛ]" else "3. Уведомления (для Android 13–14) [ВЫКЛ]",
                notifEnabled
            ) { openNotificationSettings() },
            Triple(
                "4. Настройки приложения (Автозапуск / Память)",
                false
            ) { openAppDetailsSettings() },
            Triple(
                if (isDefaultLauncher) "5. Назначить главным экраном (Для старых ТВ) [ВКЛ]" else "5. Назначить главным экраном (Для старых ТВ) [ВЫКЛ]",
                isDefaultLauncher
            ) { openHomeLauncherSettings() },
            Triple(
                "6. Кнопка Домой (Для старых ТВ): ${getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE).getString("home_launcher_name", "Автопоиск")}",
                getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE).contains("home_launcher_pkg")
            ) {
                pickingHomeLauncher = true
                btnPerm.text = "1. Назад к правам"
                listHeader.text = "Выберите родной лаунчер для кнопки ДОМОЙ:"
                loadAppsList()
            }
        )


        for ((label, isOk, action) in items) {
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 16f
                setPadding(24, 18, 24, 18)
                setTextColor(Color.WHITE)
                setBackgroundColor(if (isOk) Color.parseColor("#1B5E20") else Color.parseColor("#263238"))
                isFocusable = true
                isFocusableInTouchMode = false

                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.setBackgroundColor(Color.parseColor("#0277BD"))
                    } else {
                        v.setBackgroundColor(if (isOk) Color.parseColor("#1B5E20") else Color.parseColor("#263238"))
                    }
                }
                setOnClickListener { action() }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
            listContainer.addView(btn, params)
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
                return
            } catch (_: Exception) {}
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings()
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings()
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
                return
            }
        } catch (_: Exception) {}
        openAppDetailsSettings()
    }

    private fun openHomeLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            return
        } catch (_: Exception) {}
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(Intent.createChooser(homeIntent, "Выберите главный экран"))
        } catch (_: Exception) {
            openAppDetailsSettings()
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
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)

        if (pickingHomeLauncher) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
            } else {
                PackageManager.MATCH_DISABLED_COMPONENTS
            }
            val ignoredSystemPkgs = setOf(
                packageName,
                "com.android.settings",
                "android",
                "com.google.android.gms",
                "com.android.managedprovisioning"
            )
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val homes = packageManager.queryIntentActivities(homeIntent, flags)
                .filter { it.activityInfo.packageName !in ignoredSystemPkgs }
                .distinctBy { it.activityInfo.packageName }

            val activeHome = prefs.getString("home_launcher_pkg", null)

            // Красная кнопка сброса, если выбран ручной лаунчер
            if (!activeHome.isNullOrEmpty()) {
                val resetBtn = Button(this).apply {
                    text = "✕ Сбросить выбор (Вернуть Автопоиск)"
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 16f
                    setPadding(24, 18, 24, 18)
                    setTextColor(Color.parseColor("#FFCDD2"))
                    setBackgroundColor(Color.parseColor("#4A2C2A"))
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnFocusChangeListener { v, hasFocus ->
                        v.setBackgroundColor(if (hasFocus) Color.parseColor("#6E3B37") else Color.parseColor("#4A2C2A"))
                    }
                    setOnClickListener {
                        prefs.edit().remove("home_launcher_pkg").remove("home_launcher_name").apply()
                        Toast.makeText(this@MainActivity, "Кнопка Домой: Автопоиск", Toast.LENGTH_SHORT).show()
                        pickingHomeLauncher = false
                        switchTab(2)
                    }
                }
                val resetParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
                listContainer.addView(resetBtn, resetParams)
            }

            val options = mutableListOf<Pair<String, String?>>()
            options.add("Автопоиск (рекомендуется)" to null)

            val addedPkgs = mutableSetOf<String>()
            for (info in homes) {
                val label = info.loadLabel(packageManager).toString()
                val pkg = info.activityInfo.packageName
                options.add("[ЛАУНЧЕР] $label ($pkg)" to pkg)
                addedPkgs.add(pkg)
            }

            // Показываем только реальные приложения, у которых есть окно запуска (без библиотек и фоновых служб)
            val launchableApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter {
                    it.packageName !in ignoredSystemPkgs &&
                            it.packageName !in addedPkgs &&
                            (packageManager.getLaunchIntentForPackage(it.packageName) != null ||
                                    packageManager.getLeanbackLaunchIntentForPackage(it.packageName) != null)
                }
                .map { packageManager.getApplicationLabel(it).toString() to it.packageName }
                .sortedBy { it.first.lowercase() }

            for ((label, pkg) in launchableApps) {
                options.add("$label ($pkg)" to pkg)
            }

            for ((title, pkg) in options) {
                val isSelected = activeHome == pkg
                val btn = Button(this).apply {
                    text = if (isSelected) "[ВЫБРАНО] $title" else title
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 16f
                    setPadding(24, 18, 24, 18)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if (isSelected) Color.parseColor("#1B5E20") else Color.parseColor("#263238"))
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnFocusChangeListener { v, hasFocus ->
                        v.setBackgroundColor(when {
                            hasFocus -> Color.parseColor("#0277BD")
                            isSelected -> Color.parseColor("#1B5E20")
                            else -> Color.parseColor("#263238")
                        })
                    }
                    setOnClickListener {
                        if (pkg == null) {
                            prefs.edit().remove("home_launcher_pkg").remove("home_launcher_name").apply()
                            Toast.makeText(this@MainActivity, "Кнопка Домой: Автопоиск", Toast.LENGTH_SHORT).show()
                        } else {
                            val cleanName = title.removePrefix("[ЛАУНЧЕР] ").substringBefore(" (")
                            prefs.edit()
                                .putString("home_launcher_pkg", pkg)
                                .putString("home_launcher_name", cleanName)
                                .apply()
                            Toast.makeText(this@MainActivity, "Выбрано: $cleanName", Toast.LENGTH_SHORT).show()
                        }
                        pickingHomeLauncher = false
                        switchTab(2)
                    }
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
                listContainer.addView(btn, params)
            }
            return
        }


        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
            ).apply { setMargins(0, 0, 0, 14) }
            listContainer.addView(clearBtn, clearParams)
        }

        val keywords = listOf("tv", "livetv", "channel", "dvb", "tuner", "tele", "тв", "канал", "эфир")
        val appItems = mutableListOf<AppItem>()

        for (appInfo in packages) {
            if (appInfo.packageName == packageName) continue

            val label = pm.getApplicationLabel(appInfo).toString()
            val pkg = appInfo.packageName.lowercase()
            val lblLow = label.lowercase()

            val matchesTv = keywords.any { pkg.contains(it) || lblLow.contains(it) }
            appItems.add(AppItem(label, appInfo.packageName, matchesTv))
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
            ).apply { setMargins(0, 0, 0, 10) }
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
        Toast.makeText(this, "Автозапуск отключен (ничего не выбрано)", Toast.LENGTH_SHORT).show()
        updateStatus()
        switchTab(currentTab)
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

        var wasHomeBootHandled = false

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
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
