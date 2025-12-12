package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.Stock;
import com.stock.analyzer.model.dto.StockGraphState;

public interface StockGraphService {
    StockGraphState graphStockData(Stock result);
}

