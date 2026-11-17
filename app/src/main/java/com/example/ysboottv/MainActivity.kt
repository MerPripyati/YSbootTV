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
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
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
    private lateinit var contentScroll: ScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnPerm: Button
    private lateinit var btnDelay: Button
    private lateinit var btnAutoStart: Button
    private lateinit var btnSource: Button
    private lateinit var btnTest: Button

    private var pickingHomeLauncher = false
    private lateinit var listHeader: TextView
    private var currentTab = 0 // 0 = Приложения, 1 = Видеовыходы, 2 = Права и обход
    private var availableInputs: List<InputOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)
        handleHomeLaunch(intent)

        availableInputs = loadAvailableTvInputs()

        // Корневой контейнер: закрепленная шапка сверху + прокручиваемый список снизу
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1419"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Полноширинная закрепленная шапка
        val headerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#17212B"))
            setPadding(dp(24), dp(16), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        val title = TextView(this).apply {
            text = "YSbootTV v$appVersion — Автозапуск ТВ (Холодный + Сон)"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        }
        headerPanel.addView(title)

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 0, 0, dp(12))
        }
        headerPanel.addView(statusText)

        // Ряд главных кнопок навигации во всю ширину шапки
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnPerm = createNavButton("1. Права и обход", isLast = false) {
            pickingHomeLauncher = false
            switchTab(2)
        }
        controlsRow.addView(btnPerm)

        btnDelay = createNavButton("2. Задержка: ${prefs.getInt("delay_sec", 3)} сек", isLast = false) { btn ->
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

        btnAutoStart = createNavButton("3. Автозапуск", isLast = false) {
            pickingHomeLauncher = false
            switchTab(0)
        }
        controlsRow.addView(btnAutoStart)

        btnSource = createNavButton("4. Видеовыходы", isLast = false) {
            pickingHomeLauncher = false
            switchTab(1)
        }
        controlsRow.addView(btnSource)

        btnTest = createNavButton("5. Тест запуска", isLast = true) {
            if (hasConfiguredTarget(prefs)) {
                launchConfiguredTarget(this)
            } else {
                Toast.makeText(this, "Сначала выберите ТВ в п.3 или HDMI в п.4!", Toast.LENGTH_SHORT).show()
            }
        }
        controlsRow.addView(btnTest)

        headerPanel.addView(controlsRow)

        // Подсказка теперь внутри закрепленной шапки — никуда не уезжает при прокрутке
        listHeader = TextView(this).apply {
            text = "Выберите приложение (нажмите ОК на пульте):"
            textSize = 15f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(dp(4), dp(12), 0, 0)
        }
        headerPanel.addView(listHeader)

        rootLayout.addView(headerPanel)

        // Четкая разделительная полоса под шапкой на всю ширину экрана
        val headerDivider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2B3B4E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
            )
        }
        rootLayout.addView(headerDivider)

        // 2. Нижняя прокручиваемая рабочая область
        contentScroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val bodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bodyContainer.addView(listContainer)


        contentScroll.addView(bodyContainer)
        rootLayout.addView(contentScroll)

        setContentView(rootLayout)

        updateStatus()
        val isFirstRun = !prefs.getBoolean("first_setup_shown", false)
        if (isFirstRun) {
            prefs.edit().putBoolean("first_setup_shown", true).apply()
            switchTab(2)
            btnPerm.requestFocus()
        } else {
            switchTab(0)
            btnAutoStart.requestFocus()
        }

        try {
            TvWatchService.start(this)
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val focused = currentFocus
        if (focused != null && isDescendantOf(focused, contentScroll)) {
            contentScroll.smoothScrollTo(0, 0)
            when (currentTab) {
                2 -> btnPerm.requestFocus()
                1 -> btnSource.requestFocus()
                else -> btnAutoStart.requestFocus()
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun isDescendantOf(child: View, parent: ViewGroup): Boolean {
        var current = child.parent
        while (current != null) {
            if (current === parent) return true
            current = current.parent
        }
        return false
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
        if (currentTab == 2 && !pickingHomeLauncher) loadPermissionsList()
    }
}

private fun switchTab(tab: Int) {
    currentTab = tab
    if (::contentScroll.isInitialized) {
        contentScroll.scrollTo(0, 0)
    }
    btnPerm.text = if (pickingHomeLauncher) {
        "1. Назад к правам"
    } else {
        "1. Права и обход"
    }
    updateNavButtonsAppearance()
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

private fun updateNavButtonsAppearance() {
    if (!::btnPerm.isInitialized) return
    applyNavStyle(btnPerm, isSelectedTab = (currentTab == 2), hasFocus = btnPerm.hasFocus())
    applyNavStyle(btnDelay, isSelectedTab = false, hasFocus = btnDelay.hasFocus())
    applyNavStyle(btnAutoStart, isSelectedTab = (currentTab == 0), hasFocus = btnAutoStart.hasFocus())
    applyNavStyle(btnSource, isSelectedTab = (currentTab == 1), hasFocus = btnSource.hasFocus())
    applyNavStyle(btnTest, isSelectedTab = false, hasFocus = btnTest.hasFocus())
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
            updateNavButtonsAppearance()
            listHeader.text = "Выберите родной лаунчер для кнопки ДОМОЙ:"
            loadAppsList()
        }
    )

    for ((label, isOk, action) in items) {
        val btn = createListCardButton(
            label = label,
            isActive = isOk,
            isDanger = false,
            onClick = action
        )
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(8)) }
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
        val clearBtn = createListCardButton(
            label = "✕ Сбросить выбор (Отключить автозапуск)",
            isActive = false,
            isDanger = true
        ) { clearSelection(prefs) }
        addCardToList(clearBtn, bottomMarginDp = 10)
    }

    val inputs = loadAvailableTvInputs().filter { it.id.isNotEmpty() }
    if (inputs.isEmpty()) {
        val emptyInfo = TextView(this).apply {
            text = "Аппаратные видеовыходы (HDMI / AV) на этом устройстве не найдены."
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(dp(12), dp(18), dp(12), dp(18))
        }
        listContainer.addView(emptyInfo)
        return
    }

    for (item in inputs) {
        val isSelected = item.id == currentInputId
        val prefix = if (isSelected) "[ВЫБРАНО] " else "[ВХОД] "
        val btn = createListCardButton(
            label = "$prefix${item.label} (${item.id})",
            isActive = isSelected,
            isDanger = false
        ) {
            val activeId = prefs.getString("target_input_id", "") ?: ""
            if (activeId == item.id) {
                prefs.edit().putString("target_input_id", "").putString("target_input_label", "ВЫКЛ").apply()
                Toast.makeText(this@MainActivity, "Выбор снят: ${item.label}", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().remove("target_pkg").remove("target_name")
                    .putString("target_input_id", item.id).putString("target_input_label", item.label).apply()
                TvWatchService.start(this@MainActivity)
                Toast.makeText(this@MainActivity, "Сохранено: ${item.label}", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
            loadInputsList()
        }
        addCardToList(btn)
    }
}

private fun loadAppsList() {
    listContainer.removeAllViews()
    val prefs = getSharedPreferences("ysboot_prefs", Context.MODE_PRIVATE)

    if (pickingHomeLauncher) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        } else PackageManager.MATCH_DISABLED_COMPONENTS
        val ignoredSystemPkgs = setOf(packageName, "com.android.settings", "android", "com.google.android.gms", "com.android.managedprovisioning")
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val homes = packageManager.queryIntentActivities(homeIntent, flags)
            .filter { it.activityInfo.packageName !in ignoredSystemPkgs }
            .distinctBy { it.activityInfo.packageName }

        val activeHome = prefs.getString("home_launcher_pkg", null)
        if (!activeHome.isNullOrEmpty()) {
            val resetBtn = createListCardButton("✕ Сбросить выбор (Вернуть Автопоиск)", isActive = false, isDanger = true) {
                prefs.edit().remove("home_launcher_pkg").remove("home_launcher_name").apply()
                Toast.makeText(this@MainActivity, "Кнопка Домой: Автопоиск", Toast.LENGTH_SHORT).show()
                pickingHomeLauncher = false
                switchTab(2)
            }
            addCardToList(resetBtn, bottomMarginDp = 10)
        }

        val options = mutableListOf<Pair<String, String?>>("Автопоиск (рекомендуется)" to null)
        val addedPkgs = mutableSetOf<String>()
        for (info in homes) {
            val pkg = info.activityInfo.packageName
            options.add("[ЛАУНЧЕР] ${info.loadLabel(packageManager)} ($pkg)" to pkg)
            addedPkgs.add(pkg)
        }
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                it.packageName !in ignoredSystemPkgs && it.packageName !in addedPkgs &&
                        (packageManager.getLaunchIntentForPackage(it.packageName) != null ||
                                packageManager.getLeanbackLaunchIntentForPackage(it.packageName) != null)
            }
            .map { packageManager.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }
            .forEach { (label, pkg) -> options.add("$label ($pkg)" to pkg) }

        for ((title, pkg) in options) {
            val isSelected = activeHome == pkg
            val btn = createListCardButton(if (isSelected) "[ВЫБРАНО] $title" else title, isActive = isSelected, isDanger = false) {
                if (pkg == null) {
                    prefs.edit().remove("home_launcher_pkg").remove("home_launcher_name").apply()
                    Toast.makeText(this@MainActivity, "Кнопка Домой: Автопоиск", Toast.LENGTH_SHORT).show()
                } else {
                    val cleanName = title.removePrefix("[ЛАУНЧЕР] ").substringBefore(" (")
                    prefs.edit().putString("home_launcher_pkg", pkg).putString("home_launcher_name", cleanName).apply()
                    Toast.makeText(this@MainActivity, "Выбрано: $cleanName", Toast.LENGTH_SHORT).show()
                }
                pickingHomeLauncher = false
                switchTab(2)
            }
            addCardToList(btn)
        }
        return
    }

    val pm = packageManager
    val currentPkg = prefs.getString("target_pkg", null)
    val currentInputId = prefs.getString("target_input_id", "") ?: ""

    if (currentPkg != null || currentInputId.isNotEmpty()) {
        val clearBtn = createListCardButton("✕ Сбросить выбор (Отключить автозапуск)", isActive = false, isDanger = true) {
            clearSelection(prefs)
        }
        addCardToList(clearBtn, bottomMarginDp = 10)
    }

    val keywords = listOf("tv", "livetv", "channel", "dvb", "tuner", "tele", "тв", "канал", "эфир")
    val sorted = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.packageName != packageName }
        .map { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString()
            val matchesTv = keywords.any { appInfo.packageName.lowercase().contains(it) || label.lowercase().contains(it) }
            AppItem(label, appInfo.packageName, matchesTv)
        }
        .sortedWith(compareByDescending<AppItem> { it.isTvPriority }.thenBy { it.name })

    for (item in sorted) {
        val isSelected = item.packageName == currentPkg
        val prefix = when {
            isSelected -> "[ВЫБРАНО] "
            item.isTvPriority -> "[ТВ] "
            else -> ""
        }
        val btn = createListCardButton("$prefix${item.name} (${item.packageName})", isActive = isSelected, isDanger = false) {
            if (prefs.getString("target_pkg", null) == item.packageName) {
                prefs.edit().remove("target_pkg").remove("target_name").apply()
                Toast.makeText(this@MainActivity, "Выбор снят: ${item.name}", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("target_pkg", item.packageName).putString("target_name", item.name)
                    .putString("target_input_id", "").putString("target_input_label", "ВЫКЛ").apply()
                btnSource.text = "4. Видеовыходы"
                TvWatchService.start(this@MainActivity)
                Toast.makeText(this@MainActivity, "Сохранено: ${item.name}", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
            loadAppsList()
        }
        addCardToList(btn)
    }
}

private fun clearSelection(prefs: android.content.SharedPreferences) {
    prefs.edit().remove("target_pkg").remove("target_name")
        .putString("target_input_id", "").putString("target_input_label", "ВЫКЛ").apply()
    Toast.makeText(this, "Автозапуск отключен (ничего не выбрано)", Toast.LENGTH_SHORT).show()
    updateStatus()
    switchTab(currentTab)
}

private fun createNavButton(label: String, isLast: Boolean, onClick: (Button) -> Unit): Button {
    return Button(this).apply {
        text = label
        isAllCaps = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        textSize = 13.5f
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        isFocusable = true
        isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        applyNavStyle(this, isSelectedTab = false, hasFocus = false)
        setOnFocusChangeListener { _, _ -> updateNavButtonsAppearance() }
        setOnClickListener { onClick(this) }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, if (isLast) 0 else dp(10), 0)
        }
    }
}

    private fun applyNavStyle(btn: Button, isSelectedTab: Boolean, hasFocus: Boolean) {
        val fillColor = if (hasFocus) Color.parseColor("#0277BD") else Color.parseColor("#243240")
        val strokeColor = if (hasFocus) Color.parseColor("#81D4FA") else Color.parseColor("#354759")
        val strokeWidth = if (hasFocus) dp(2) else dp(1)

        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
    }

private fun createListCardButton(label: String, isActive: Boolean, isDanger: Boolean, onClick: () -> Unit): Button {
    return Button(this).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textSize = 15.5f
        setPadding(dp(18), dp(13), dp(18), dp(13))
        setTextColor(if (isDanger) Color.parseColor("#FFCDD2") else Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        applyCardStyle(this, isActive, isDanger, hasFocus = false)
        setOnFocusChangeListener { _, hasFocus -> applyCardStyle(this, isActive, isDanger, hasFocus) }
        setOnClickListener { onClick() }
    }
}
private fun applyCardStyle(btn: Button, isActive: Boolean, isDanger: Boolean, hasFocus: Boolean) {
    val fill = when {
        isDanger && hasFocus -> Color.parseColor("#7A3531")
        isDanger -> Color.parseColor("#4A2624")
        hasFocus -> Color.parseColor("#0277BD")
        isActive -> Color.parseColor("#1B5E20")
        else -> Color.parseColor("#1B242C")
    }
    val stroke = when {
        isDanger && hasFocus -> Color.parseColor("#EF9A9A")
        isDanger -> Color.parseColor("#6E3835")
        hasFocus -> Color.parseColor("#81D4FA")
        isActive -> Color.parseColor("#4CAF50")
        else -> Color.parseColor("#2A3642")
    }
    btn.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(if (hasFocus) dp(2) else dp(1), stroke)
    }
}
private fun addCardToList(view: View, bottomMarginDp: Int = 8) {
    val params = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(bottomMarginDp)) }
    listContainer.addView(view, params)
}
private fun dp(value: Int): Int {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
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
