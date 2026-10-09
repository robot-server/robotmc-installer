package com.sysbot32.robotmc.installer.prelaunch

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 설치기와 같이 https 구성을 먼저 보고, 실패하면 캐시, 그것도 없으면 설치할 때 복사해 둔 구성 파일을 쓴다.
 * manifest URL 이 비어 있으면 캐시를 보지 않고 그 파일을 쓴다.
 */
fun loadRemoteConfig(
    manifestUrl: String?,
    cacheFile: Path,
    bundledFile: Path,
    timeout: Duration = REMOTE_CONFIG_TIMEOUT,
): String {
    val url = manifestUrl?.trim().orEmpty()
    if (url.isNotEmpty()) {
        try {
            val remote = fetchHttps(URI.create(url), timeout)
            if (usable(remote)) {
                return remote
            }
        } catch (exception: Exception) {
            // 받기에 실패하면 아래 캐시를 본다.
        }
        readUsable(cacheFile)?.let { return it }
    }
    try {
        return Files.readString(bundledFile)
    } catch (exception: Exception) {
        throw IllegalStateException("Bundled installer config is missing", exception)
    }
}

const val REMOTE_CONFIG_TIMEOUT_SECONDS = 5L
val REMOTE_CONFIG_TIMEOUT: Duration = Duration.ofSeconds(REMOTE_CONFIG_TIMEOUT_SECONDS)
private const val MAX_BYTES = 256 * 1024
private const val MAX_REDIRECTS = 3

private fun readUsable(file: Path?): String? {
    if (file == null || !Files.isRegularFile(file)) {
        return null
    }
    return try {
        val text = Files.readString(file)
        if (usable(text)) text else null
    } catch (exception: Exception) {
        null
    }
}

private fun usable(yamlText: String): Boolean {
    return try {
        InstallerYaml.parse(yamlText).loaderId != null
    } catch (exception: Exception) {
        false
    }
}

private fun fetchHttps(uri: URI, timeout: Duration): String {
    if (!isHttps(uri)) {
        throw IllegalArgumentException("Manifest URL must use https")
    }
    val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    var current = uri
    for (redirect in 0..MAX_REDIRECTS) {
        val request = HttpRequest.newBuilder(current)
            .timeout(timeout)
            .header("Accept", "application/yaml, text/yaml, text/plain, */*")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val status = response.statusCode()
            if (status in 200..299) {
                return readLimited(body, timeout)
            }
            if (status in 300..399) {
                val location = response.headers().firstValue("location")
                    .orElseThrow { IllegalStateException("Redirect is missing location") }
                val next = current.resolve(location.trim())
                if (!isHttps(next)) {
                    throw IllegalArgumentException("Redirect left https")
                }
                current = next
                return@use
            }
            throw IllegalStateException("Manifest request failed: $status")
        }
    }
    throw IllegalStateException("Manifest redirected too many times")
}

private fun readLimited(input: InputStream, timeout: Duration): String {
    val future = CompletableFuture.supplyAsync {
        try {
            val bytes = input.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) {
                throw IllegalArgumentException("Manifest is too large")
            }
            bytes
        } catch (exception: Exception) {
            if (exception is RuntimeException) {
                throw exception
            }
            throw IllegalStateException(exception)
        }
    }
    try {
        val bytes = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join()
        return String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
    } catch (exception: CompletionException) {
        input.close()
        val cause = exception.cause
        if (cause is TimeoutException) {
            throw IllegalStateException("Manifest body timed out", cause)
        }
        if (cause is Exception) {
            throw cause
        }
        throw exception
    }
}

private fun isHttps(uri: URI): Boolean {
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}
