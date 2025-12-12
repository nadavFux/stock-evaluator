package com.stock.analyzer.model.dto;

import java.io.Serializable;
import java.util.List;

public record ExecutionResult(List<StockCheckResult> stocks, Double gains) implements Serializable {
}

