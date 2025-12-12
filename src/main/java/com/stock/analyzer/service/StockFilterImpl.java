package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.BaseStock;

import java.util.List;

public class StockFilterImpl implements StockFilter {


    private final List<String> allowedExchanges;
    private final double minMarketCap;
    private final double minAssessment;

    public StockFilterImpl(List<String> allowed_exchanges, double min_market_cap, double min_assessment) {
        allowedExchanges = allowed_exchanges;
        minMarketCap = min_market_cap;
        minAssessment = min_assessment;
    }

    @Override
    public List<BaseStock> filterStockData(List<BaseStock> stocks) {
        return stocks.stream()
                .filter(stock -> stock.market_cap_before_filing_date() >= this.minMarketCap)
                .filter(stock -> stock.final_assessment() >= this.minAssessment)
                .filter(stock -> this.allowedExchanges.contains(stock.exchange_symbol())).toList();

    }
}

