package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.BaseStock;

import java.util.List;

public interface StockFilter {
    List<BaseStock> filterStockData(List<BaseStock> stocks);
}

