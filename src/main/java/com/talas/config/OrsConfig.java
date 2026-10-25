package com.talas.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/** HTTP client for OpenRouteService. The API key is sent as the Authorization header (no "Bearer"). */
@Slf4j
@Configuration
public class OrsConfig {

    @Bean
    public RestClient orsRestClient(@Value("${ors.base-url}") String baseUrl,
            @Value("${ors.api-key}") String apiKey) {
        // Without a key ORS answers 401, so place search and routing would fail with a generic message
        if (apiKey.isBlank()) {
            log.warn("ORS_API_KEY is not set: place search and routing will not work until it is.");
        }

        // Give up instead of hanging forever if ORS is slow or unreachable
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(10));
        timeouts.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", apiKey)
                // Buffering sends each JSON body in one piece with a Content-Length header (not "chunked")
                .requestFactory(new BufferingClientHttpRequestFactory(timeouts))
                .build();
    }
}
