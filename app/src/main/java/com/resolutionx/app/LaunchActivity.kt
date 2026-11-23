package com.resolutionx.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** 홈 화면 바로가기용: 해상도를 먼저 낮춘 뒤 게임을 실행한다. */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        if (pkg == null || pkg !in Prefs(this).games) { finish(); return }

        Thread {
            val msg = if (Shell.awaitReady()) {
                GameWatcherService.ensureRunning(this) // 게임 종료 시 복구가 되도록
                Actions.enterGame(this, pkg)
            } else "Shizuku가 실행 중이 아니라 해상도를 바꾸지 못했습니다"
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                finish()
            }
        }.start()
    }

    companion object { const val EXTRA_PKG = "pkg" }
}
