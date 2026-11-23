package com.resolutionx.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("resolutionx", Context.MODE_PRIVATE)

    var games: Set<String>
        get() = sp.getStringSet("games", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("games", v).apply()

    /** 화면 전체 해상도 축소 사용 여부 (wm size / wm density) */
    var useScreen: Boolean
        get() = sp.getBoolean("useScreen", true)
        set(v) = sp.edit().putBoolean("useScreen", v).apply()

    /** 물리 해상도 대비 비율(%) */
    var scalePct: Int
        get() = sp.getInt("scalePct", -1)
        set(v) = sp.edit().putInt("scalePct", v).apply()

    /** 게임별 다운스케일 사용 여부 (cmd game) */
    var useDownscale: Boolean
        get() = sp.getBoolean("useDownscale", false)
        set(v) = sp.edit().putBoolean("useDownscale", v).apply()

    /** 다운스케일 비율(%) — 5% 단위 */
    var downscalePct: Int
        get() = sp.getInt("downscalePct", 75)
        set(v) = sp.edit().putInt("downscalePct", v).apply()

    /** 게임 진입/이탈 시 자동 적용/복구 */
    var autoSwitch: Boolean
        get() = sp.getBoolean("autoSwitch", true)
        set(v) = sp.edit().putBoolean("autoSwitch", v).apply()

    /** 현재 이 앱이 적용해 둔 화면 해상도 ("WxH@dpi"), 없으면 null */
    var appliedScreen: String?
        get() = sp.getString("appliedScreen", null)
        set(v) = sp.edit().putString("appliedScreen", v).apply()

    /** 다운스케일을 적용해 둔 게임 목록 (해제할 때 사용) */
    var downscaledGames: Set<String>
        get() = sp.getStringSet("downscaledGames", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("downscaledGames", v).apply()

    /** 사용자가 자동 전환 서비스를 켜 두었는지 (강제 종료 시 시스템이 목록에서 빼버리므로 따로 기억) */
    var serviceWanted: Boolean
        get() = sp.getBoolean("serviceWanted", false)
        set(v) = sp.edit().putBoolean("serviceWanted", v).apply()

    var physical: String?
        get() = sp.getString("physical", null)
        set(v) = sp.edit().putString("physical", v).apply()
}
