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
    double riskFreeRate,
    double buyThreshold,
    
    // Scoring Weights
    double movingAvgGapWeight,
    double reversionToMeanWeight,
    double ratingWeight,
    double upwardIncRateWeight,
    double rvolWeight,
    double pegWeight,
    double volatilityCompressionWeight
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
        private double buyThreshold;
        
        private double movingAvgGapWeight;
        private double reversionToMeanWeight;
        private double ratingWeight;
        private double upwardIncRateWeight;
        private double rvolWeight;
        private double pegWeight;
        private double volatilityCompressionWeight;

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
            this.buyThreshold = other.buyThreshold();
            
            this.movingAvgGapWeight = other.movingAvgGapWeight();
            this.reversionToMeanWeight = other.reversionToMeanWeight();
            this.ratingWeight = other.ratingWeight();
            this.upwardIncRateWeight = other.upwardIncRateWeight();
            this.rvolWeight = other.rvolWeight();
            this.pegWeight = other.pegWeight();
            this.volatilityCompressionWeight = other.volatilityCompressionWeight();
        }

        public Builder sellCutOffPerc(double val) { this.sellCutOffPerc = val; return this; }
        public Builder lowerPriceToLongAvgBuyIn(double val) { this.lowerPriceToLongAvgBuyIn = val; return this; }
        public Builder higherPriceToLongAvgBuyIn(double val) { this.higherPriceToLongAvgBuyIn = val; return this; }
        public Builder timeFrameForUpwardLongAvg(int val) { this.timeFrameForUpwardLongAvg = val; return this; }
        public Builder aboveAvgRatingPricePerc(double val) { this.aboveAvgRatingPricePerc = val; return this; }
        public Builder timeFrameForUpShortPrice(int val) { this.timeFrameForUpwardShortPrice = val; return this; }
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
        public Builder buyThreshold(double val) { this.buyThreshold = val; return this; }
        
        public Builder movingAvgGapWeight(double val) { this.movingAvgGapWeight = val; return this; }
        public Builder reversionToMeanWeight(double val) { this.reversionToMeanWeight = val; return this; }
        public Builder ratingWeight(double val) { this.ratingWeight = val; return this; }
        public Builder upwardIncRateWeight(double val) { this.upwardIncRateWeight = val; return this; }
        public Builder rvolWeight(double val) { this.rvolWeight = val; return this; }
        public Builder pegWeight(double val) { this.pegWeight = val; return this; }
        public Builder volatilityCompressionWeight(double val) { this.volatilityCompressionWeight = val; return this; }

        public SimulationParams build() {
            return new SimulationParams(
                sellCutOffPerc, lowerPriceToLongAvgBuyIn, higherPriceToLongAvgBuyIn, timeFrameForUpwardLongAvg, 
                aboveAvgRatingPricePerc, timeFrameForUpwardShortPrice, timeFrameForOscillator, maxRSI, 
                minMarketCap, longMovingAvgTime, minRateOfAvgInc, maxPERatio, minRating, maxRating, 
                maxMarketCap, riskFreeRate, buyThreshold,
                movingAvgGapWeight, reversionToMeanWeight, ratingWeight, upwardIncRateWeight, rvolWeight, pegWeight, volatilityCompressionWeight
            );
        }
    }

    public Builder toBuilder() {
        return new Builder(this);
    }
}
