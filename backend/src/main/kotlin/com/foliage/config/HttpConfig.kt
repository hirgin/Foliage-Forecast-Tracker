package com.foliage.config

import org.springframework.boot.web.client.RestClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class HttpConfig {

    /**
     * Ingest talks to third-party services that are occasionally slow. Timeouts
     * are generous but finite: a hung request must eventually fail so the job
     * can record it and resume, rather than blocking a bootstrap indefinitely.
     */
    @Bean
    fun restClient(): RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(15))
                setReadTimeout(Duration.ofSeconds(60))
            },
        )
        .build()
}
