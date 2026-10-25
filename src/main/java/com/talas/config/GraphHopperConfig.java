package com.talas.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/** HTTP client for GraphHopper. Its API key travels as the "key" query parameter, added by each provider. */
@Slf4j
@Configuration
public class GraphHopperConfig {

    @Bean
    public RestClient graphHopperRestClient(@Value("${graphhopper.base-url}") String baseUrl,
            @Value("${graphhopper.api-key}") String apiKey) {
        if (apiKey.isBlank()) {
            log.warn("GRAPHHOPPER_API_KEY is not set: GraphHopper place search and routing will not work until it is.");
        }

        // Give up instead of hanging forever if GraphHopper is slow or unreachable
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(10));
        timeouts.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
                .baseUrl(baseUrl)
                // Buffering sends each JSON body in one piece with a Content-Length header (not "chunked")
                .requestFactory(new BufferingClientHttpRequestFactory(timeouts))
                .build();
    }

    /**
     * Short text for the log. The default exception message contains the full URL, which includes the
     * API key, so only the exception type and the HTTP status are logged.
     */
    public static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }
}
