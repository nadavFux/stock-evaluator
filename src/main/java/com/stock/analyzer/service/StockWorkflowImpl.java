package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.ExecutionResult;
import com.stock.analyzer.core.StockProcessor;

public class StockWorkflowImpl implements StockWorkflow {
    private final StockProcessor processor;

    public StockWorkflowImpl(StockFetcher stockFetcher,
            StockFilter stockFilter,
            StockEnricher stockEnricher,
            StockGraphService stockGraphService,
            StockAnalysisStrategy stockAnalysisStrategy) {
        this.processor = new StockProcessor(
                stockFetcher,
                stockFilter,
                stockEnricher,
                stockGraphService,
                stockAnalysisStrategy);
    }

    @Override
    public ExecutionResult getStockData(int sector) {
        return processor.getStockData(sector);
    }
}
