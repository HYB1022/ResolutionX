package com.resolutionx.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/**
 * 포그라운드 앱 전환을 감지해서
 *  - 대상 게임이 화면에 뜨면 해상도를 낮추고
 *  - 게임을 벗어나 몇 초가 지나면 원래 해상도로 되돌린다.
 */
class GameWatcherService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var lastPkg: String? = null
    private val restoreTask = Runnable { maybeRestore() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        // 팝업/토스트/키보드 등은 무시하고 실제 액티비티 전환만 본다
        if (!isActivity(pkg, cls)) { Log.d(TAG, "skip non-activity $pkg/$cls"); return }
        if (pkg == lastPkg) return
        lastPkg = pkg
        Log.i(TAG, "foreground: $pkg/$cls")

        val prefs = Prefs(this)
        if (!prefs.autoSwitch) return

        if (pkg in prefs.games) {
            main.removeCallbacks(restoreTask)
            worker.execute { log(Actions.enterGame(this, pkg)) }
        } else if (prefs.appliedScreen != null) {
            // 최근 앱 화면을 잠깐 여는 경우 등을 고려해 지연 후 복구
            main.removeCallbacks(restoreTask)
            main.postDelayed(restoreTask, RESTORE_DELAY_MS)
        }
    }

    private fun maybeRestore() {
        val prefs = Prefs(this)
        if (gameStillVisible(prefs.games)) { Log.i(TAG, "game window still visible, keep"); return }
        worker.execute { log(Actions.exitGame(this)) }
    }

    /** 게임 위에 반투명 오버레이(게임 도우미 등)가 떠 있는 경우 게임 창이 아직 보인다. */
    private fun gameStillVisible(games: Set<String>): Boolean = try {
        windows.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() in games
        }
    } catch (_: Throwable) { false }

    private fun isActivity(pkg: String, cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true
    } catch (_: Exception) { false }

    private fun log(msg: String) { if (msg.isNotBlank()) Log.i(TAG, msg) }

    /** 서비스가 (프로세스 종료 후) 다시 붙었을 때: 게임 밖인데 해상도가 낮게 남아 있으면 복구 */
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Prefs(this).serviceWanted = true
        worker.execute {
            if (!Shell.awaitReady(5000)) return@execute
            val prefs = Prefs(this)
            val state = Resolution.readState() ?: return@execute
            if (state.override == null) { prefs.appliedScreen = null; return@execute }
            if (prefs.appliedScreen == null) return@execute // 사용자가 직접 바꾼 해상도는 건드리지 않음
            val top = Resolution.foregroundPackage()
            lastPkg = top
            if (top != null && top !in prefs.games) log("재연결: " + Actions.exitGame(this))
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        main.removeCallbacks(restoreTask)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ResolutionX"
        private const val RESTORE_DELAY_MS = 3000L

        /** 서비스가 이 프로세스에서 실제로 바인드되어 돌고 있는지 */
        @Volatile var running = false
            private set

        fun component(ctx: Context) = ComponentName(ctx, GameWatcherService::class.java)

        /**
         * 사용자가 켜 두었는데 앱 강제 종료로 서비스가 끊긴 경우(시스템이 활성 목록에서도 제거함),
         * Shizuku로 다시 등록한다. 백그라운드 스레드에서 호출.
         */
        fun needsReconnect(ctx: Context) = !running && Prefs(ctx).let { it.serviceWanted && it.autoSwitch }

        fun ensureRunning(ctx: Context): String {
            if (!needsReconnect(ctx) || !Shell.hasPermission()) return ""
            return "자동 전환 서비스 재연결: " + enableViaShell(ctx)
        }

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(ctx)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Shizuku로 접근성 서비스를 켠다 (사이드로드 앱의 '제한된 설정' 우회). */
        fun enableViaShell(ctx: Context): String {
            val me = component(ctx).flattenToString()
            val others = Shell.run("settings get secure enabled_accessibility_services").out
                .takeUnless { it.isBlank() || it == "null" }
                ?.split(':')?.filter { it.isNotBlank() && it != me }.orEmpty()
            val without = others.joinToString(":")
            val with = (others + me).joinToString(":")
            // 이미 등록돼 있어도 한 번 뺐다 넣어야 강제 종료 후 다시 바인드된다
            val r = Shell.run(
                (if (without.isEmpty()) "settings delete secure enabled_accessibility_services"
                 else "settings put secure enabled_accessibility_services '$without'") +
                    " && sleep 0.3 && settings put secure enabled_accessibility_services '$with'" +
                    " && settings put secure accessibility_enabled 1"
            )
            // 배터리 최적화 예외: 시스템이 백그라운드에서 강제 종료할 가능성을 줄인다
            Shell.run("cmd deviceidle whitelist +${ctx.packageName}")
            return if (r.ok) "자동 전환(접근성 서비스) 켜짐" else "접근성 서비스 켜기 실패: $r"
        }
    }
}
