package com.stock.analyzer.service;

import com.google.gson.*;
import com.stock.analyzer.model.BaseStock;
import com.stock.analyzer.model.Root;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.infra.HttpClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public StockDataService(HttpClientService httpClient, String scannerUrl, String identifierUrl, String techUrl, Map<String, String> authHeaders) {
        this.httpClient = httpClient;
        this.scannerUrl = scannerUrl;
        this.identifierUrl = identifierUrl;
        this.techUrl = techUrl;
        this.authHeaders = authHeaders;
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
