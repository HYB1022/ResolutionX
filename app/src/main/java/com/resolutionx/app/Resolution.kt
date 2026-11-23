package com.resolutionx.app

import android.content.Context
import kotlin.math.max
import kotlin.math.roundToInt

data class Display(val w: Int, val h: Int, val dpi: Int) {
    fun scaled(pct: Int): Display {
        fun even(v: Double) = (v / 2).roundToInt() * 2
        return Display(even(w * pct / 100.0), even(h * pct / 100.0), (dpi * pct / 100.0).roundToInt())
    }
    val sizeText get() = "${w}x${h}"
    override fun toString() = "$sizeText @ ${dpi}dpi"

    companion object {
        fun parse(s: String?): Display? {
            val m = Regex("""(\d+)x(\d+)@(\d+)""").matchEntire(s ?: return null) ?: return null
            val (w, h, d) = m.destructured
            return Display(w.toInt(), h.toInt(), d.toInt())
        }
    }
    fun encode() = "${w}x${h}@${dpi}"
}

/** 실제 해상도 변경 로직. 모두 백그라운드 스레드에서 호출할 것. */
object Resolution {

    /** 참고 글 권장값: 3K(3040) → 약 2300 */
    const val RECOMMENDED_LONG_SIDE = 2300

    data class State(val physical: Display, val override: Display?)

    fun readState(): State? {
        val size = Shell.run("wm size").out
        val dens = Shell.run("wm density").out
        val ps = Regex("""Physical size:\s*(\d+)x(\d+)""").find(size) ?: return null
        val pd = Regex("""Physical density:\s*(\d+)""").find(dens) ?: return null
        val physical = Display(ps.groupValues[1].toInt(), ps.groupValues[2].toInt(), pd.groupValues[1].toInt())
        val os = Regex("""Override size:\s*(\d+)x(\d+)""").find(size)
        val od = Regex("""Override density:\s*(\d+)""").find(dens)
        val override = if (os != null || od != null) Display(
            os?.groupValues?.get(1)?.toInt() ?: physical.w,
            os?.groupValues?.get(2)?.toInt() ?: physical.h,
            od?.groupValues?.get(1)?.toInt() ?: physical.dpi,
        ) else null
        return State(physical, override)
    }

    fun recommendedPct(physical: Display): Int =
        (RECOMMENDED_LONG_SIDE * 100.0 / max(physical.w, physical.h)).roundToInt().coerceIn(50, 100)

    fun physical(ctx: Context): Display? {
        val p = Prefs(ctx)
        Display.parse(p.physical)?.let { return it }
        val s = readState() ?: return null
        p.physical = s.physical.encode()
        return s.physical
    }

    /** 화면 전체 해상도·DPI를 비율만큼 낮춘다. */
    fun applyScreen(ctx: Context, pct: Int): String {
        val p = Prefs(ctx)
        val phys = physical(ctx) ?: return "물리 해상도를 읽지 못했습니다 (Shizuku 확인)"
        val target = phys.scaled(pct)
        // 저장값이 아니라 실제 상태로 판단 (워치독이 대신 복구했을 수 있음)
        if (readState()?.override == target) {
            p.appliedScreen = target.encode()
            startWatchdog(p.games)
            return "이미 적용됨: $target"
        }
        val r = Shell.run("wm size ${target.sizeText} && wm density ${target.dpi}")
        return if (r.ok) {
            p.appliedScreen = target.encode()
            startWatchdog(p.games)
            "화면 해상도 적용: $target"
        } else "화면 해상도 적용 실패: $r"
    }

    private const val WATCHDOG = "/data/local/tmp/resolutionx_watchdog.sh"

    /**
     * 셸(Shizuku) 쪽에서 앱과 독립적으로 도는 감시 스크립트.
     * 앱 프로세스가 죽은 상태에서 게임을 벗어나면 해상도를 복구하고 종료한다.
     * 앱이 살아 있으면 pidof만 확인하므로 부하가 거의 없다.
     */
    private fun startWatchdog(games: Set<String>) {
        val script = """
            PIDF=/data/local/tmp/resolutionx_watchdog.pid
            [ -f ${'$'}PIDF ] && kill ${'$'}(cat ${'$'}PIDF) 2>/dev/null
            echo ${'$'}${'$'} > ${'$'}PIDF
            GAMES=" ${'$'}1 "
            miss=0
            while :; do
              sleep 2
              wm size | grep -q Override || break
              if pidof ${BuildConfig.APPLICATION_ID} >/dev/null; then miss=0; continue; fi
              top=${'$'}(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed -E 's/.* u0 ([^ /]+).*/\1/')
              [ -z "${'$'}top" ] && continue
              case "${'$'}GAMES" in *" ${'$'}top "*) miss=0 ;; *) miss=${'$'}((miss+1)) ;; esac
              if [ ${'$'}miss -ge 2 ]; then wm size reset; wm density reset; break; fi
            done
            rm -f ${'$'}PIDF
        """.trimIndent()
        val pkgs = games.joinToString(" ")
        Shell.run(
            "cat > $WATCHDOG <<'RXEOF'\n$script\nRXEOF\n" +
                "setsid sh $WATCHDOG '$pkgs' </dev/null >/dev/null 2>&1 &"
        )
    }

    /** 현재 포그라운드 앱 패키지 (Shizuku 필요) */
    fun foregroundPackage(): String? =
        Regex(""" u0 ([^ /]+)/""").find(
            Shell.run("dumpsys activity activities | grep -m1 topResumedActivity").out
        )?.groupValues?.get(1)

    fun resetScreen(ctx: Context): String {
        val r = Shell.run("wm size reset && wm density reset")
        return if (r.ok) {
            Prefs(ctx).appliedScreen = null
            "화면 해상도 복구 완료"
        } else "복구 실패: $r"
    }

    /**
     * Android Game Mode 다운스케일 (Android 13+). 해당 게임만 낮은 해상도로 렌더링.
     * 게임을 다시 시작해야 반영된다.
     */
    fun applyDownscale(ctx: Context, pkg: String, pct: Int): String {
        val f = "%.2f".format(java.util.Locale.US, pct.coerceIn(30, 90) / 100.0)
        // Android 14+: `set`이 바로 custom 모드(4)로 전환한다.
        var r = Shell.run("cmd game set --downscale $f $pkg")
        if (!r.out.contains("Set custom mode", true)) {
            // Android 13: performance 모드에 개입 설정 후 모드 전환
            r = Shell.run("cmd game set --mode 2 --downscale $f $pkg && cmd game mode performance $pkg")
        }
        return if (r.ok && !r.out.contains("error", true) && !r.out.contains("Unknown", true) && !r.out.contains("not supported", true)) {
            val p = Prefs(ctx); p.downscaledGames = p.downscaledGames + pkg
            "다운스케일 $f 적용: $pkg" + if (r.out.isNotBlank()) "\n${r.out}" else ""
        } else "다운스케일 실패($pkg): $r"
    }

    fun resetDownscale(ctx: Context, pkg: String): String {
        val r = Shell.run("cmd game mode standard $pkg; cmd game reset $pkg")
        val p = Prefs(ctx); p.downscaledGames = p.downscaledGames - pkg
        return if (r.ok) "다운스케일 해제: $pkg" else "다운스케일 해제 실패($pkg): $r"
    }
}

/** 게임 진입/이탈 시 공통 동작 */
object Actions {
    fun enterGame(ctx: Context, pkg: String): String {
        val p = Prefs(ctx)
        val log = mutableListOf<String>()
        if (p.useScreen) log += Resolution.applyScreen(ctx, effectivePct(ctx))
        if (p.useDownscale && pkg !in p.downscaledGames) log += Resolution.applyDownscale(ctx, pkg, p.downscalePct)
        return log.joinToString("\n").ifEmpty { "적용할 설정이 꺼져 있습니다" }
    }

    fun exitGame(ctx: Context): String =
        if (Prefs(ctx).appliedScreen != null) Resolution.resetScreen(ctx) else ""

    fun effectivePct(ctx: Context): Int {
        val p = Prefs(ctx)
        if (p.scalePct > 0) return p.scalePct
        return Resolution.physical(ctx)?.let { Resolution.recommendedPct(it) } ?: 76
    }
}
