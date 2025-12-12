package com.stock.analyzer.model.dto;

import java.io.Serializable;

public record StockCheckResult(StockGraphState stock, SimulationResult result) implements Serializable {
}
