package com.stock.analyzer.model;

public record TrainingSample(
    double maGap,
    double distFromMA,
    double rating,
    double momentum,
    double rvol,
    double peg,
    double volatility,
    double actualGain
) {
    public double[] getFeatures() {
        return new double[]{maGap, distFromMA, rating, momentum, rvol, peg, volatility};
    }
}

