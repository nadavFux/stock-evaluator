package com.stock.analyzer.core;

public record SimulationConfig(
        double sellCutOffPerc,
        double lowerPriceToLongAvgBuyIn,
        double higherPriceToLongAvgBuyIn,
        int timeFrameForUpwardLongAvg,
        double aboveAvgRatingPricePerc,
        int timeFrameForUpwardShortPrice,
        int timeFrameForOsilator,
        double maxRSI,
        double minMarketCap,
        int longMovingAvgTime,
        double minRateOfAvgInc,
        int maxPERatio,
        double minRating,
        double maxRating,
        double maxMarketCap) {
    public String generateKey() {
        return sellCutOffPerc + "," + lowerPriceToLongAvgBuyIn + "," + higherPriceToLongAvgBuyIn + ","
                + timeFrameForUpwardLongAvg + "," + aboveAvgRatingPricePerc + "," + timeFrameForUpwardShortPrice + ","
                + timeFrameForOsilator + "," + maxRSI + "," + minMarketCap + "," + longMovingAvgTime + ","
                + minRateOfAvgInc + "," + maxPERatio + "," + minRating + "," + maxRating + "," + maxMarketCap;
    }
}
