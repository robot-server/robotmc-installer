package com.sysbot32.robotmc.installer

/**
 * 릴리스 태그 ref 가 정확히 `v<major>.<minor>.<patch>` 일 때만 그 숫자 버전을 쓴다.
 * 로컬 빌드, 브랜치, pull request, 빈 값, 커밋 거리(`v1.2.0-3-gabcdef`)는 [FALLBACK] 이다.
 * 각 숫자는 0 이거나 앞자리가 0 이 아닌 비음수 정수다.
 */
object InstallerVersion {
    const val FALLBACK: String = "1.0.0"

    private val exactReleaseTag = Regex("""v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")

    fun versionFor(ref: String?): String {
        if (ref.isNullOrEmpty()) {
            return FALLBACK
        }
        val match = exactReleaseTag.matchEntire(ref) ?: return FALLBACK
        return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
    }
}
