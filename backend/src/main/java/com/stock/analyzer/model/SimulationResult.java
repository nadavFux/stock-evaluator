package com.stock.analyzer.model;

import java.io.Serializable;

public record SimulationResult(
    double heuristicScore,
    double aiPredictedReturn,
    double rvol,
    double volatility,
    double momentum,
    double[] features
) implements Serializable {
    public SimulationResult(double heuristicScore, double aiPredictedReturn) {
        this(heuristicScore, aiPredictedReturn, 0.0, 0.0, 0.0, null);
    }

    public double getEval() {
        return aiPredictedReturn != -1.0 ? aiPredictedReturn : heuristicScore;
    }
}
