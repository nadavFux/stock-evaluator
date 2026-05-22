package com.stock.analyzer.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stock.analyzer.infra.HttpClientService;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.StockGraphState;
import com.stock.analyzer.model.StocksGraphRequestBody;
import com.stock.analyzer.model.StocksSearchRequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stock.analyzer.infra.StockCacheRepository;
import com.stock.analyzer.model.StockCacheEntity;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class GraphingService {
    private static final Logger logger = LoggerFactory.getLogger(GraphingService.class);
    private final HttpClientService httpClient;
    private final String searchUrl;
    private final String graphUrl;
    private final StockCacheRepository cacheRepository;
    private final Gson gson = new Gson();

    public GraphingService(HttpClientService httpClient, String searchUrl, String graphUrl, StockCacheRepository cacheRepository) {
        this.httpClient = httpClient;
        this.searchUrl = searchUrl;
        this.graphUrl = graphUrl;
        this.cacheRepository = cacheRepository;
    }

    public StockGraphState getCachedState(String ticker) {
        if (ticker == null) return null;
        String normalizedTicker = ticker.trim().toUpperCase();
        var cached = cacheRepository.findById(normalizedTicker);
        if (cached.isPresent() && cached.get().getLastUpdated().equals(LocalDate.now())) {
            try {
                StockGraphState state = gson.fromJson(cached.get().getGraphDataJson(), StockGraphState.class);
                if (state != null && state.closePrices() != null && !state.closePrices().isEmpty()) {
                    logger.info("Cache HIT for {}", normalizedTicker);
                    return state;
                }
            } catch (Exception e) {
                logger.warn("Failed to deserialize graph cache for {}, fetching fresh.", normalizedTicker);
            }
        }
        return null;
    }

    public StockGraphState fetchGraphState(Stock stock) {
        String ticker = stock.ticker_symbol().trim().toUpperCase();
        StockGraphState cached = getCachedState(ticker);
        if (cached != null) return cached;

        logger.info("Cache MISS for {}. Starting fresh fetch.", ticker);

        try {
            String kid = resolveKid(ticker, stock.exchange_symbol());
            if (kid == null) {
                logger.warn("Extraction FAILED for {}: Could not resolve KID (Exchange: {})", ticker, stock.exchange_symbol());
                return null;
            }

            // Fetch and parse graph data
            var candleBody = gson.toJson(new StocksGraphRequestBody(kid, "p_candle_range", 1200));
            String graphResponse = httpClient.post(graphUrl, candleBody, null).join();
            if (graphResponse == null) {
                logger.warn("Extraction FAILED for {}: Empty graph response from API", ticker);
                return null;
            }

            JsonObject graphRoot = JsonParser.parseString(graphResponse).getAsJsonObject();
            if (!graphRoot.has("graph") || graphRoot.get("graph").isJsonNull()) {
                logger.warn("Extraction FAILED for {}: Response missing 'graph' object", ticker);
                return null;
            }

            JsonObject graphJson = graphRoot.getAsJsonObject("graph");
            List<Double> close = extractArray(graphJson, "close");
            List<Double> volume = extractArray(graphJson, "volume");
            List<String> dates = extractStringArray(graphJson, "date");

            if (close.isEmpty() || dates.isEmpty()) {
                logger.warn("Extraction FAILED for {}: Basic price/date data is empty", ticker);
                return null;
            }

            // Fetch metrics
            List<Double> ratings = fetchMetric(kid, "fest_est_ar_avg_no");
            List<Double> avgs = fetchMetric(kid, "fest_estpt");
            List<Double> epss = fetchMetric(kid, "fest_esteps_ntm");
            List<Double> caps = fetchMetric(kid, "f_mkt");

            int size = Collections.min(Arrays.asList(close.size(), ratings.size(), volume.size(), avgs.size(), dates.size(), epss.size(), caps.size()));
            if (size == 0) {
                logger.warn("Extraction FAILED for {}: Incomplete indicator data (Close: {}, Ratings: {}, Vol: {}, Targets: {}, Dates: {}, EPS: {}, Caps: {})", 
                        ticker, close.size(), ratings.size(), volume.size(), avgs.size(), dates.size(), epss.size(), caps.size());
                return null;
            }

            logger.info("Successfully fetched graph data for {}", ticker);
            StockGraphState result = new StockGraphState(stock,
                    tail(ratings, size), 0.0, 0.0, close.get(close.size() - 1),
                    tail(close, size), tail(volume, size), tail(avgs, size), tail(dates, size), tail(epss, size), tail(caps, size));

            cacheRepository.save(new StockCacheEntity(ticker, LocalDate.now(), gson.toJson(result)));
            return result;

        } catch (Exception e) {
            logger.error("Failed to fetch graph for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    private String resolveKid(String ticker, String exchange) {
        String kid = searchForKid(ticker);
        if (kid != null) return kid;

        if ("TASE".equals(exchange)) {
            kid = searchForKid(ticker + ".IT");
            if (kid == null) kid = searchForKid(ticker + ":IT");
        }
        return kid;
    }

    private String searchForKid(String ticker) {
        try {
            var searchBody = gson.toJson(new StocksSearchRequestBody(ticker));
            String searchResponse = httpClient.post(searchUrl, searchBody, null).join();
            if (searchResponse == null) return null;

            JsonObject searchJson = JsonParser.parseString(searchResponse).getAsJsonObject();
            if (!searchJson.has("data")) return null;
            var dataArray = searchJson.getAsJsonArray("data");
            if (dataArray.size() == 0) return null;
            
            return dataArray.get(0).getAsJsonObject().getAsJsonPrimitive("KID").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Double> extractArray(JsonObject graphJson, String key) {
        if (!graphJson.has(key) || graphJson.get(key).isJsonNull()) return List.of();
        return graphJson.getAsJsonArray(key).asList().stream()
                .map(JsonElement::getAsDouble)
                .toList();
    }

    private List<String> extractStringArray(JsonObject graphJson, String key) {
        if (!graphJson.has(key) || graphJson.get(key).isJsonNull()) return List.of();
        return graphJson.getAsJsonArray(key).asList().stream()
                .map(JsonElement::getAsString)
                .toList();
    }

    private List<Double> fetchMetric(String kid, String type) {
        var body = gson.toJson(new StocksGraphRequestBody(kid, type, 1200));
        Optional<String> response = Optional.ofNullable(httpClient.post(graphUrl, body, null).join());
        return response.map(res -> JsonParser.parseString(res).getAsJsonObject())
                .map(jsonObject -> jsonObject.getAsJsonObject("graph"))
                .map(graph -> graph.getAsJsonArray("value"))
                .map(values -> values.asList().stream().map(JsonElement::getAsDouble).toList())
                .orElse(List.of());
    }

    private <T> List<T> tail(List<T> list, int n) {
        return list.subList(list.size() - n, list.size());
    }
}
