package com.stock.analyzer.model;

import java.io.Serializable;
import java.util.List;

public record StockCheckResult(StockGraphState stock, SimulationResult result, List<TradePoint> tradePoints) implements Serializable {
}
