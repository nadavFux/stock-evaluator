package com.stock.analyzer.model;

import java.io.Serializable;

public record SimulationResult(
    double heuristicScore,
    double aiPredictedReturn,
    double rvol,
    double volatility,
    double momentum,
    double[] features,
    double q05,
    double q50,
    double q95
) implements Serializable {
    public SimulationResult(double heuristicScore, double aiPredictedReturn) {
        this(heuristicScore, aiPredictedReturn, 0.0, 0.0, 0.0, null, 0.0, 0.0, 0.0);
    }

    public SimulationResult(double heuristicScore, double aiPredictedReturn, double rvol, double volatility, double momentum, double[] features) {
        this(heuristicScore, aiPredictedReturn, rvol, volatility, momentum, features, 0.0, 0.0, 0.0);
    }

    public double getEval() {
        return aiPredictedReturn != -1.0 ? aiPredictedReturn : heuristicScore;
    }
}
