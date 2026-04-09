package com.stock.analyzer.model;

public record SimulationParams(
    double sellCutOffPerc,
    double lowerPriceToLongAvgBuyIn,
    double higherPriceToLongAvgBuyIn,
    int timeFrameForUpwardLongAvg,
    double aboveAvgRatingPricePerc,
    int timeFrameForUpwardShortPrice,
    int timeFrameForOscillator,
    double maxRSI,
    double minMarketCap,
    int longMovingAvgTime,
    double minRateOfAvgInc,
    int maxPERatio,
    double minRating,
    double maxRating,
    double maxMarketCap,
    double riskFreeRate
) {
    public static class Builder {
        private double sellCutOffPerc;
        private double lowerPriceToLongAvgBuyIn;
        private double higherPriceToLongAvgBuyIn;
        private int timeFrameForUpwardLongAvg;
        private double aboveAvgRatingPricePerc;
        private int timeFrameForUpwardShortPrice;
        private int timeFrameForOscillator;
        private double maxRSI;
        private double minMarketCap;
        private int longMovingAvgTime;
        private double minRateOfAvgInc;
        private int maxPERatio;
        private double minRating;
        private double maxRating;
        private double maxMarketCap;
        private double riskFreeRate;

        public Builder(SimulationParams other) {
            this.sellCutOffPerc = other.sellCutOffPerc();
            this.lowerPriceToLongAvgBuyIn = other.lowerPriceToLongAvgBuyIn();
            this.higherPriceToLongAvgBuyIn = other.higherPriceToLongAvgBuyIn();
            this.timeFrameForUpwardLongAvg = other.timeFrameForUpwardLongAvg();
            this.aboveAvgRatingPricePerc = other.aboveAvgRatingPricePerc();
            this.timeFrameForUpwardShortPrice = other.timeFrameForUpwardShortPrice();
            this.timeFrameForOscillator = other.timeFrameForOscillator();
            this.maxRSI = other.maxRSI();
            this.minMarketCap = other.minMarketCap();
            this.longMovingAvgTime = other.longMovingAvgTime();
            this.minRateOfAvgInc = other.minRateOfAvgInc();
            this.maxPERatio = other.maxPERatio();
            this.minRating = other.minRating();
            this.maxRating = other.maxRating();
            this.maxMarketCap = other.maxMarketCap();
            this.riskFreeRate = other.riskFreeRate();
        }

        public Builder sellCutOffPerc(double val) { this.sellCutOffPerc = val; return this; }
        public Builder lowerPriceToLongAvgBuyIn(double val) { this.lowerPriceToLongAvgBuyIn = val; return this; }
        public Builder higherPriceToLongAvgBuyIn(double val) { this.higherPriceToLongAvgBuyIn = val; return this; }
        public Builder timeFrameForUpwardLongAvg(int val) { this.timeFrameForUpwardLongAvg = val; return this; }
        public Builder aboveAvgRatingPricePerc(double val) { this.aboveAvgRatingPricePerc = val; return this; }
        public Builder timeFrameForUpwardShortPrice(int val) { this.timeFrameForUpwardShortPrice = val; return this; }
        public Builder timeFrameForOscillator(int val) { this.timeFrameForOscillator = val; return this; }
        public Builder maxRSI(double val) { this.maxRSI = val; return this; }
        public Builder minMarketCap(double val) { this.minMarketCap = val; return this; }
        public Builder longMovingAvgTime(int val) { this.longMovingAvgTime = val; return this; }
        public Builder minRateOfAvgInc(double val) { this.minRateOfAvgInc = val; return this; }
        public Builder maxPERatio(int val) { this.maxPERatio = val; return this; }
        public Builder minRating(double val) { this.minRating = val; return this; }
        public Builder maxRating(double val) { this.maxRating = val; return this; }
        public Builder maxMarketCap(double val) { this.maxMarketCap = val; return this; }
        public Builder riskFreeRate(double val) { this.riskFreeRate = val; return this; }

        public SimulationParams build() {
            return new SimulationParams(sellCutOffPerc, lowerPriceToLongAvgBuyIn, higherPriceToLongAvgBuyIn, timeFrameForUpwardLongAvg, aboveAvgRatingPricePerc, timeFrameForUpwardShortPrice, timeFrameForOscillator, maxRSI, minMarketCap, longMovingAvgTime, minRateOfAvgInc, maxPERatio, minRating, maxRating, maxMarketCap, riskFreeRate);
        }
    }

    public Builder toBuilder() {
        return new Builder(this);
    }
}
