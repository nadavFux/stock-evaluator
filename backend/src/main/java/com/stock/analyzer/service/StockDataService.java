package com.stock.analyzer.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stock.analyzer.infra.HttpClientService;
import com.stock.analyzer.infra.StockCacheRepository;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StockDataService {
    private static final Logger logger = LoggerFactory.getLogger(StockDataService.class);
    private final HttpClientService httpClient;
    private final String scannerUrl;
    private final String identifierUrl;
    private final String techUrl;
    private final Map<String, String> authHeaders;
    private final StockCacheRepository cacheRepository;
    private final Gson gson = new Gson();

    public StockDataService(HttpClientService httpClient, String scannerUrl, String identifierUrl, String techUrl, Map<String, String> authHeaders, StockCacheRepository cacheRepository) {
        this.httpClient = httpClient;
        this.scannerUrl = scannerUrl;
        this.identifierUrl = identifierUrl;
        this.techUrl = techUrl;
        this.authHeaders = authHeaders;
        this.cacheRepository = cacheRepository;
    }

    private static final Map<Integer, String> SECTOR_NAMES = Map.ofEntries(
            Map.entry(10, "Energy"), Map.entry(15, "Materials"), Map.entry(20, "Industrials"),
            Map.entry(25, "Cons. Disc"), Map.entry(30, "Cons. Staples"), Map.entry(35, "Health Care"),
            Map.entry(40, "Financials"), Map.entry(45, "Tech"), Map.entry(50, "Comm"),
            Map.entry(55, "Utilities"), Map.entry(60, "Real Estate")
    );

    public SectorPerformance calculateSectorPerformance(int sectorId, List<String> exchanges) {
        List<BaseStock> stocks = fetchAndFilter(sectorId, exchanges, 0.0);
        if (stocks.isEmpty()) return new SectorPerformance("Unknown", sectorId, 0.0, 0.0, 0);

        // Map final_assessment (0-100) to a centered return proxy (-0.5 to 0.5)
        double avgScore = stocks.stream().mapToDouble(BaseStock::final_assessment).average().orElse(50.0);
        double performanceProxy = (avgScore - 50.0) / 100.0;

        double totalCap = stocks.stream().mapToDouble(BaseStock::market_cap_before_filing_date).sum();
        String name = SECTOR_NAMES.getOrDefault(sectorId, "Sector " + sectorId);

        return new SectorPerformance(name, sectorId, performanceProxy, totalCap, stocks.size());
    }

    public List<BaseStock> fetchAndFilter(int sector, List<String> exchanges, double minCap) {
        String url = scannerUrl.replace("{code}", String.valueOf(sector));
        String body = httpClient.get(url, authHeaders).join();

        if (body == null) return List.of();

        JsonObject element = JsonParser.parseString(body).getAsJsonObject();
        JsonObject data = element.getAsJsonObject().getAsJsonObject("data");
        data.remove("raw");

        Root root = new Gson().fromJson(data, Root.class);
        List<BaseStock> stocks = DataConverter.convert(root);

        return stocks.stream()
                .filter(s -> exchanges.contains(s.exchange_symbol()))
                .filter(s -> s.market_cap_before_filing_date() > minCap)
                .collect(Collectors.toList());
    }
public Stock enrich(BaseStock base) {
    try {
        JsonObject idData = getIdentifier(base.ticker_symbol());
        if (idData == null) idData = getIdentifier("IL-" + base.ticker_symbol());
        if (idData == null) return null;

        String isin = idData.getAsJsonPrimitive("isin").getAsString();
        String fullName = idData.getAsJsonPrimitive("companyName").getAsString();

        Double techScore = fetchTechScore(isin);

        return new Stock(base.company_id(), base.name(), base.ticker_symbol(), base.exchange_symbol(),
                base.filing_date(), base.market_cap_before_filing_date(), base.final_assessment(),
                base.buying_recommendation(), isin, fullName, techScore);
    } catch (Exception e) {
        logger.error("Failed to enrich stock {}: {}", base.ticker_symbol(), e.getMessage());
        return null;
    }
}


    private JsonObject getIdentifier(String ticker) {
        String body = httpClient.get(identifierUrl + ticker, authHeaders).join();
        if (body == null) return null;
        JsonArray data = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
        return data.size() > 0 ? data.get(0).getAsJsonObject() : null;
    }

    private Double fetchTechScore(String isin) {
        String url = techUrl.replace("{id}", isin);
        String body = httpClient.get(url, authHeaders).join();
        if (body == null) return null;
        try {
            return JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("data").getAsJsonArray("indicators").get(0)
                    .getAsJsonObject().getAsJsonPrimitive("technical_indicator").getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }
}
