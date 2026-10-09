package com.sysbot32.robotmc.installer.prelaunch

import java.nio.file.Path

/**
 * 프로필 javaArgs 에 -javaagent 를 하나만 둔다.
 * -Xmx 같은 기존 토큰은 그대로 두고, 다시 써도 같은 토큰을 두 번 넣지 않는다.
 */
fun mergeJavaAgent(javaArgs: String?, agentJar: Path, optionsFile: Path): String {
    val agentToken = agentToken(agentJar, optionsFile)
    val merged = mutableListOf<String>()
    var placed = false
    for (token in tokenizeJavaArgs(javaArgs)) {
        if (isJavaAgentToken(token)) {
            if (!placed) {
                merged += agentToken
                placed = true
            }
            continue
        }
        merged += token
    }
    if (!placed) {
        merged += agentToken
    }
    return joinJavaArgs(merged)
}

fun tokenizeJavaArgs(javaArgs: String?): List<String> {
    if (javaArgs.isNullOrBlank()) {
        return emptyList()
    }
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (character in javaArgs) {
        if (character == '"') {
            quoted = !quoted
            continue
        }
        if (!quoted && character.isWhitespace()) {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
            continue
        }
        current.append(character)
    }
    if (quoted) {
        throw IllegalArgumentException("javaArgs has an unfinished quote")
    }
    if (current.isNotEmpty()) {
        tokens += current.toString()
    }
    return tokens
}

fun isJavaAgentToken(token: String): Boolean = token.startsWith("-javaagent:")

fun javaAgentJar(token: String): Path {
    val spec = agentSpec(token)
    return Path.of(spec.substring(0, optionsSeparator(spec)))
}

fun javaAgentOptionsFile(token: String): Path {
    val spec = agentSpec(token)
    return Path.of(spec.substring(optionsSeparator(spec) + 1))
}

private fun agentToken(agentJar: Path, optionsFile: Path): String {
    val jar = agentJar.toAbsolutePath().normalize().toString()
    val options = optionsFile.toAbsolutePath().normalize().toString()
    return "-javaagent:$jar=$options"
}

private fun agentSpec(token: String): String {
    if (!isJavaAgentToken(token)) {
        throw IllegalArgumentException("not a javaagent token")
    }
    return token.removePrefix("-javaagent:")
}

private fun optionsSeparator(spec: String): Int {
    val separator = spec.indexOf('=')
    if (separator <= 0 || separator == spec.length - 1) {
        throw IllegalArgumentException("javaagent token is missing options")
    }
    return separator
}

private fun joinJavaArgs(tokens: List<String>): String {
    return tokens.joinToString(" ") { token -> quoteIfNeeded(token) }
}

private fun quoteIfNeeded(token: String): String {
    if (token.any { it.isWhitespace() }) {
        return "\"$token\""
    }
    return token
}
