package com.resolutionx.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var tvShizuku: TextView
    private lateinit var btnShizuku: Button
    private lateinit var tvA11y: TextView
    private lateinit var btnA11y: Button
    private lateinit var tvDisplay: TextView
    private lateinit var swScreen: Switch
    private lateinit var tvScale: TextView
    private lateinit var sbScale: SeekBar
    private lateinit var swDownscale: Switch
    private lateinit var tvDownscale: TextView
    private lateinit var sbDownscale: SeekBar
    private lateinit var swAuto: Switch
    private lateinit var gamesContainer: LinearLayout
    private lateinit var tvLog: TextView

    private var physical: Display? = null

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshAll(); reconnectService() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshAll() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        // Android 15+ edge-to-edge: 상태바·내비게이션바·카메라 컷아웃만큼 여백
        findViewById<android.view.View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        tvShizuku = findViewById(R.id.tvShizuku)
        btnShizuku = findViewById(R.id.btnShizuku)
        tvA11y = findViewById(R.id.tvA11y)
        btnA11y = findViewById(R.id.btnA11y)
        tvDisplay = findViewById(R.id.tvDisplay)
        swScreen = findViewById(R.id.swScreen)
        tvScale = findViewById(R.id.tvScale)
        sbScale = findViewById(R.id.sbScale)
        swDownscale = findViewById(R.id.swDownscale)
        tvDownscale = findViewById(R.id.tvDownscale)
        sbDownscale = findViewById(R.id.sbDownscale)
        swAuto = findViewById(R.id.swAuto)
        gamesContainer = findViewById(R.id.gamesContainer)
        tvLog = findViewById(R.id.tvLog)

        btnShizuku.setOnClickListener { requestShizuku() }
        btnA11y.setOnClickListener { enableAccessibility() }

        swScreen.isChecked = prefs.useScreen
        swScreen.setOnCheckedChangeListener { _, on -> prefs.useScreen = on }

        sbScale.setOnSeekBarChangeListener(seek { v ->
            prefs.scalePct = v + 50
            updateScaleLabel()
        })
        findViewById<Button>(R.id.btnRecommended).setOnClickListener {
            physical?.let { prefs.scalePct = Resolution.recommendedPct(it) }
            sbScale.progress = Actions.effectivePct(this) - 50
            updateScaleLabel()
        }

        swDownscale.isChecked = prefs.useDownscale
        swDownscale.setOnCheckedChangeListener { _, on ->
            prefs.useDownscale = on
            if (!on) bg { prefs.downscaledGames.map { Resolution.resetDownscale(this, it) }.joinToString("\n") }
        }
        sbDownscale.progress = (prefs.downscalePct - 30) / 5
        sbDownscale.setOnSeekBarChangeListener(seek { v ->
            prefs.downscalePct = 30 + v * 5
            prefs.downscaledGames = emptySet() // 값이 바뀌었으니 다음 진입 때 다시 적용
            updateDownscaleLabel()
        })
        updateDownscaleLabel()

        swAuto.isChecked = prefs.autoSwitch
        swAuto.setOnCheckedChangeListener { _, on -> prefs.autoSwitch = on }

        findViewById<Button>(R.id.btnAddGame).setOnClickListener { pickGames() }
        findViewById<Button>(R.id.btnApply).setOnClickListener { applyNow() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetNow() }

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)

        renderGames()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        reconnectService()
    }

    /** 강제 종료로 서비스가 끊겼으면 자동으로 다시 붙인다 */
    private fun reconnectService() {
        if (GameWatcherService.needsReconnect(this) && Shell.hasPermission()) {
            bg { GameWatcherService.ensureRunning(this).also { Thread.sleep(800) } }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- 상태 ----------

    private fun refreshAll() {
        refreshStatus()
        if (Shell.hasPermission()) worker.execute {
            val state = Resolution.readState()
            if (state != null) prefs.physical = state.physical.encode()
            runOnUiThread {
                physical = state?.physical
                tvDisplay.text = if (state == null) "해상도 정보를 읽지 못했습니다" else
                    "기본: ${state.physical}\n현재: ${state.override ?: state.physical}" +
                        if (state.override != null) "  (변경됨)" else ""
                sbScale.progress = Actions.effectivePct(this) - 50
                updateScaleLabel()
            }
        } else {
            tvDisplay.text = "Shizuku 연결 후 해상도 정보가 표시됩니다"
            sbScale.progress = Actions.effectivePct(this).coerceIn(50, 100) - 50
            updateScaleLabel()
        }
    }

    private fun refreshStatus() {
        val alive = Shell.binderAlive()
        val granted = Shell.hasPermission()
        tvShizuku.text = when {
            !alive -> "Shizuku: 실행 안 됨 ✗"
            !granted -> "Shizuku: 권한 필요"
            else -> "Shizuku: 연결됨 ✓"
        }
        btnShizuku.text = if (!alive) "Shizuku 열기" else "권한 요청"
        btnShizuku.isEnabled = !granted

        val running = GameWatcherService.running
        val enabled = GameWatcherService.isEnabled(this) || prefs.serviceWanted
        tvA11y.text = when {
            running -> "자동 전환 서비스: 켜짐 ✓"
            enabled -> "자동 전환 서비스: 멈춤 (앱이 강제 종료됨)"
            else -> "자동 전환 서비스: 꺼짐"
        }
        btnA11y.text = if (enabled && !running) "다시 켜기" else "켜기"
        btnA11y.isEnabled = !running
    }

    private fun updateScaleLabel() {
        val pct = sbScale.progress + 50
        val target = physical?.scaled(pct)
        tvScale.text = "비율 $pct%" + (target?.let { " → $it" } ?: "")
    }

    private fun updateDownscaleLabel() {
        tvDownscale.text = "렌더 배율 ${"%.2f".format(Locale.US, prefs.downscalePct / 100.0)}"
    }

    // ---------- 동작 ----------

    private fun requestShizuku() {
        if (!Shell.binderAlive()) {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i)
            else {
                toast("Shizuku 앱을 먼저 설치하고 실행해 주세요")
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
            }
            return
        }
        if (Shizuku.isPreV11()) { toast("Shizuku 버전이 너무 낮습니다"); return }
        Shizuku.requestPermission(REQ_SHIZUKU)
    }

    private fun enableAccessibility() {
        if (Shell.hasPermission()) {
            bg { GameWatcherService.enableViaShell(this).also { Thread.sleep(800) } }
        } else {
            toast("설정 > 접근성에서 ResolutionX를 켜 주세요")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun applyNow() {
        val games = prefs.games
        bg {
            val log = mutableListOf<String>()
            if (prefs.useScreen) log += Resolution.applyScreen(this, Actions.effectivePct(this))
            if (prefs.useDownscale) games.forEach { log += Resolution.applyDownscale(this, it, prefs.downscalePct) }
            log.joinToString("\n").ifEmpty { "사용 중인 방식이 없습니다. 스위치를 켜 주세요." }
        }
    }

    private fun resetNow() = bg {
        val log = mutableListOf(Resolution.resetScreen(this))
        prefs.downscaledGames.forEach { log += Resolution.resetDownscale(this, it) }
        log.joinToString("\n")
    }

    // ---------- 게임 목록 ----------

    private data class AppEntry(val pkg: String, val label: String, val isGame: Boolean) {
        val isRemote get() = RemoteApps.isRemote(pkg, label)
    }

    private fun launchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.category == ApplicationInfo.CATEGORY_GAME) }
            .sortedWith(compareBy<AppEntry>({ !it.isGame }, { it.label.lowercase() }))
    }

    private fun pickGames() {
        val apps = launchableApps()
        val selected = prefs.games.toMutableSet()
        val labels = apps.map {
            (if (it.isGame) "🎮 " else "") + it.label + (if (it.isRemote) " ⚠ 원격 앱" else "")
        }.toTypedArray()
        val checked = apps.map { it.pkg in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("해상도를 낮출 게임 선택")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                if (on) selected += apps[i].pkg else selected -= apps[i].pkg
            }
            .setPositiveButton("확인") { _, _ ->
                // 새로 추가한 원격 앱만 경고한다 (이미 경고를 보고 남겨 둔 앱은 다시 묻지 않음).
                val newRemote = apps.filter { it.isRemote && it.pkg in selected && it.pkg !in prefs.games }
                if (newRemote.isEmpty()) saveGames(selected)
                else warnRemote(newRemote.map { it.label }) { keep ->
                    saveGames(if (keep) selected else selected - newRemote.map { it.pkg }.toSet())
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveGames(selected: Set<String>) {
        val removed = prefs.games - selected
        prefs.games = selected
        removed.filter { it in prefs.downscaledGames }.forEach { pkg -> bg { Resolution.resetDownscale(this, pkg) } }
        renderGames()
    }

    private fun warnRemote(labels: List<String>, onChoice: (keep: Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("원격 스트리밍 앱은 권장하지 않습니다")
            .setMessage(
                "${labels.joinToString(", ")}은(는) 원격 스트리밍 앱입니다.\n\n" +
                    "• 스트리밍 중에 해상도가 바뀌면 소리와 입력은 되는데 화면만 멈출 수 있습니다.\n" +
                    "• Apollo 같은 호스트는 태블릿 해상도에 맞춰 PC 가상 디스플레이를 만듭니다. " +
                    "낮춘 해상도로 접속하면 PC 게임이 그 해상도를 저장해서, 나중에 원래 해상도로 접속해도 화면이 멈출 수 있습니다.\n\n" +
                    "스트리밍 해상도는 원격 앱 설정에서 바꾸는 것을 권장합니다."
            )
            .setPositiveButton("빼고 저장") { _, _ -> onChoice(false) }
            .setNegativeButton("그래도 추가") { _, _ -> onChoice(true) }
            .setCancelable(false)
            .show()
    }

    private fun renderGames() {
        gamesContainer.removeAllViews()
        val pm = packageManager
        val games = prefs.games
        if (games.isEmpty()) {
            gamesContainer.addView(TextView(this).apply {
                text = "선택된 게임이 없습니다. '게임 선택'을 눌러 추가하세요."
                alpha = 0.7f
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        for (pkg in games.sorted()) {
            val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { null }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(ImageView(this).apply {
                info?.let { setImageDrawable(it.loadIcon(pm)) }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            })
            val label = info?.loadLabel(pm)?.toString()
            row.addView(TextView(this).apply {
                text = (label ?: "$pkg (설치 안 됨)") +
                    if (RemoteApps.isRemote(pkg, label)) "\n⚠ 원격 앱: 스트리밍 화면이 멈출 수 있음" else ""
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            if (info != null) {
                row.addView(Button(this).apply {
                    text = "실행"
                    setOnClickListener { startActivity(launchIntent(pkg)) }
                })
                row.addView(Button(this).apply {
                    text = "바로가기"
                    setOnClickListener { pinShortcut(pkg, info) }
                })
            }
            gamesContainer.addView(row)
        }
    }

    private fun launchIntent(pkg: String) =
        Intent(this, LaunchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(LaunchActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun pinShortcut(pkg: String, info: ApplicationInfo) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { toast("런처가 바로가기 추가를 지원하지 않습니다"); return }
        val label = info.loadLabel(packageManager).toString()
        val shortcut = ShortcutInfo.Builder(this, "launch_$pkg")
            .setShortLabel("$label (RX)")
            .setLongLabel("$label - 해상도 낮춰서 실행")
            .setIcon(Icon.createWithBitmap(info.loadIcon(packageManager).toBitmap(dp(108))))
            .setIntent(launchIntent(pkg))
            .build()
        sm.requestPinShortcut(shortcut, null)
    }

    // ---------- 유틸 ----------

    /** 백그라운드에서 실행하고 결과를 로그에 남긴다. */
    private fun bg(block: () -> String) {
        worker.execute {
            val msg = try { block() } catch (e: Throwable) { "오류: ${e.message}" }
            runOnUiThread {
                appendLog(msg)
                refreshAll()
            }
        }
    }

    private fun appendLog(msg: String) {
        if (msg.isBlank()) return
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.text = "[$t] $msg\n" + tvLog.text
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun Drawable.toBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(c)
        return bmp
    }

    private fun seek(onUser: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) onUser(v) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    companion object { private const val REQ_SHIZUKU = 1001 }
}
