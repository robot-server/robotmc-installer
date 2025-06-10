package com.sysbot32.robotmc.installer.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

private val log = KotlinLogging.logger { }

@Configuration
class HttpClientConfig {
    @Bean
    fun restClient(): RestClient {
        return RestClient.builder()
            .requestInterceptor { request, body, execution ->
                log.info { "${request.method} ${request.uri}" }
                val response = execution.execute(request, body)
                log.info { "${response.statusCode}" }
                return@requestInterceptor response
            }
            .build()
    }
}
