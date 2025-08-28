package com.sysbot32.robotmc.installer.exception

/**
 * 사용자 예외
 */
open class UserException(
    override val message: String? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * 종료 상태
     */
    open val exitStatus: Int
        get() = -400

    /**
     * 예외 발생 후 동작
     */
    open fun afterThrow() {
    }
}
