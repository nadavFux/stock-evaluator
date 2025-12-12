package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.ExecutionResult;

public interface StockWorkflow {
    ExecutionResult getStockData(int sector);
}

