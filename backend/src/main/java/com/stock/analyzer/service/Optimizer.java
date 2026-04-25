package com.stock.analyzer.service;

import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.StockGraphState;
import java.util.List;

/**
 * Interface for parameter optimization strategies.
 * Supports different implementations (CPU, GPU, etc.)
 */
public interface Optimizer {
    SimulationParams optimize(List<StockGraphState> allStocks);
    MLModelService getMlService();

    record CandidateResult(SimulationParams params, double score) {}
}
