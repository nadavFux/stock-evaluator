package com.stock.analyzer.model;

public record ScoringWeights(
    double movingAvgGapWeight,
    double reversionToMeanWeight,
    double ratingWeight,
    double upwardIncRateWeight,
    double rvolWeight,
    double pegWeight,
    double volatilityCompressionWeight
) {
    public static ScoringWeights defaultWeights() {
        return new ScoringWeights(0.20, 0.15, 0.20, 0.15, 0.10, 0.10, 0.10);
    }
}

