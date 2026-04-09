package com.stock.analyzer.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class HttpClientService {
    private static final Logger logger = LoggerFactory.getLogger(HttpClientService.class);
    private final HttpClient client;
    private final Map<String, Semaphore> rateLimiters = new ConcurrentHashMap<>();
    private final int permitsPerHost;

    public HttpClientService(int permitsPerHost) {
        this.permitsPerHost = permitsPerHost;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<String> get(String url, Map<String, String> headers) {
        String host = URI.create(url).getHost();
        Semaphore limiter = rateLimiters.computeIfAbsent(host, k -> new Semaphore(permitsPerHost));

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("acquire 1 {} {} ", url, limiter);
                limiter.acquire();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET();

                if (headers != null) {
                    headers.forEach(builder::header);
                }

                HttpRequest request = builder.build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    logger.error("GET to {} failed with status: {}", url, response.statusCode());
                    return null;
                }
                return response.body();
            } catch (Throwable e) {
                return null;
            } finally {
                logger.debug("release 1 {} {} ", url, limiter);
                limiter.release();
            }
        });
    }

    public CompletableFuture<String> post(String url, String jsonBody, Map<String, String> headers) {
        String host = URI.create(url).getHost();
        Semaphore limiter = rateLimiters.computeIfAbsent(host, k -> new Semaphore(permitsPerHost));

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.debug("acquire 2 {} {} ", url, limiter);
                limiter.acquire();
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

                if (headers != null) {
                    headers.forEach(builder::header);
                }
                builder.header("Content-Type", "application/json");

                HttpRequest request = builder.build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    logger.error("POST to {} failed with status: {}", url, response.statusCode());
                    return null;
                }
                return response.body();
            } catch (Throwable e) {
                return null;
            } finally {
                logger.debug("release 2 {} {}", url, limiter);
                limiter.release();
            }
        });
    }
}
