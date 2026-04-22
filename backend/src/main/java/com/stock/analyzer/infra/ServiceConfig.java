package com.stock.analyzer.infra;

import com.stock.analyzer.service.GraphingService;
import com.stock.analyzer.service.StockDataService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class ServiceConfig {

    @Bean
    public HttpClientService httpClientService() {
        return new HttpClientService(15);
    }

    @Bean
    public StockDataService stockDataService(HttpClientService httpClientService) {
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("api-key", "6a3a617f15f02e5302b849d18123bb5a32b3b0154ad2a3ddf55e7b5f66e39132");
        authHeaders.put("date-format", "epoch");
        authHeaders.put("Referer", "https://plus.tase.co.il/");

        return new StockDataService(httpClientService,
                "https://api.bridgewise.com/v2/scanner?n=4000&gics={code}&last_n_days=1000&raw=true&metadata=true&score=true&price_equity=true&language=he-IL",
                "https://apipa.tase.co.il/tr/assets/",
                "https://api.bridgewise.com/v2/technical-analysis?identifier={id}&summary=true&language=he-IL&short_name=true",
                authHeaders);
    }

    @Bean
    public GraphingService graphingService(HttpClientService httpClientService) {
        return new GraphingService(httpClientService,
                "https://app.koyfin.com/api/v1/bfc/tickers/search",
                "https://app.koyfin.com/api/v3/data/graph?schema=packed");
    }
}
