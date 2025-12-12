package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.BaseStock;
import com.stock.analyzer.model.dto.Stock;

public interface StockEnricher {
    Stock enrichStockData(BaseStock result);
}

