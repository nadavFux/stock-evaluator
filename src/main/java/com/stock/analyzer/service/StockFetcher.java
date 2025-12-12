package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.BaseStock;

import java.io.IOException;
import java.util.List;

public interface StockFetcher {
    List<BaseStock> fetchStockData(int sector) throws IOException, InterruptedException;
}

