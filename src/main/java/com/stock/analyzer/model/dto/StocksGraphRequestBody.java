package com.stock.analyzer.model.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class StocksGraphRequestBody {
    private final String id;
    private final String key;
    private final String currency = "USD";
    private final String candleAggregationPeriod = "day";
    private final String dateFrom;
    private final String dateTo = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

    // Constructor
    public StocksGraphRequestBody(String id, String key, int timeDifference) {
        this.id = id;
        this.key = key;
        dateFrom = LocalDate.now().minusDays(timeDifference).format(DateTimeFormatter.ISO_DATE);
    }
}

