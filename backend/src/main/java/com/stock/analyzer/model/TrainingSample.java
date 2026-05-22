package com.stock.analyzer.model;

public record TrainingSample(
    float[][] sequence,
    float actualGain
) {
}

