package com.resolutionx.app

/**
 * 원격 스트리밍 앱 판별.
 * 스트리밍 중에 화면 해상도가 바뀌면 영상만 멈추는 문제가 생길 수 있고,
 * 호스트(Apollo 등)가 낮춘 해상도로 가상 디스플레이를 만들어 PC 게임 설정까지 바뀔 수 있다.
 */
object RemoteApps {
    // Moonlight와 Artemis 등 포크는 com.limelight 계열 패키지를 쓴다.
    private val PACKAGE_PREFIXES = listOf(
        "com.limelight",
        "com.valvesoftware.steamlink",
        "com.parsec.",
        "com.playstation.remoteplay",
        "com.nvidia.geforcenow",
    )

    // 패키지 이름을 모르는 포크나 다른 앱을 위한 보조 판별.
    private val LABEL_KEYWORDS = listOf(
        "moonlight", "artemis", "steam link", "parsec", "remote play", "geforce now", "chiaki", "rainway",
    )

    fun isRemote(pkg: String, label: String?): Boolean {
        if (PACKAGE_PREFIXES.any { pkg.startsWith(it) }) return true
        val l = label?.lowercase() ?: return false
        return LABEL_KEYWORDS.any { it in l }
    }
}
