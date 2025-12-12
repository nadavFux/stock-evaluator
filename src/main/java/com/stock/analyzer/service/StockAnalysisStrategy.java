package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.StockCheckResult;
import com.stock.analyzer.model.dto.StockGraphState;

public interface StockAnalysisStrategy {
    StockCheckResult checkStockData(StockGraphState result);
}

