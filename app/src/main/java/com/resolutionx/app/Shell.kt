package com.resolutionx.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku(셸 권한)로 명령을 실행한다. */
object Shell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
        override fun toString() = buildString {
            if (out.isNotBlank()) append(out)
            if (err.isNotBlank()) { if (isNotEmpty()) append('\n'); append(err) }
            if (!ok && isEmpty()) append("실패 (code $code)")
        }
    }

    // Shizuku 13의 newProcess는 private이라 리플렉션으로 호출한다.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** 앱이 막 시작됐을 때 Shizuku 바인더가 붙을 때까지 잠깐 기다린다. */
    fun awaitReady(timeoutMs: Long = 2500): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasPermission()) return true
            Thread.sleep(100)
        }
        return hasPermission()
    }

    fun run(cmd: String): Result {
        if (!hasPermission()) return Result(-1, "", "Shizuku가 연결되지 않았거나 권한이 없습니다")
        return try {
            val p = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            Result(p.waitFor(), out.trim(), err.trim())
        } catch (e: Throwable) {
            Result(-1, "", (e.cause ?: e).message ?: e.toString())
        }
    }
}
