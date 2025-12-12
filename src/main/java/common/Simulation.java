package common;

import common.DTO.Stock;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class Simulation {
    public final HashMap<String, StocksTradeTimeFrame> timeFrames;

    private final double sellCutOffPerc;
    private final double lowerPriceToLongAvgBuyIn;
    private final double higherPriceToLongAvgBuyIn;
    private final int timeFrameForUpwardLongAvg;
    private final double aboveAvgRatingPricePerc;
    private final int timeFrameForUpwardShortPrice;
    private final int timeFrameForOsilator;
    private final double maxRSI;
    private final double minMarketCap;
    private final double maxMarketCap;
    private final double minRateOfAvgInc;
    private final int maxPERatio;

    public final String key;
    private final double minRating;
    private final double maxRating;

    public double getEval() {
        return eval;
    }

    public double eval;


    public Simulation(double sellCutOffPerc, double lowerPriceToLongAvgBuyIn, double higherPriceToLongAvgBuyIn, int timeFrameForUpwardLongAvg, double aboveAvgRatingPricePerc, int timeFrameForUpwardShortPrice, int timeFrameForOsilator, double maxRSI, double minMarketCap, int longMovingAvgTime, double minRateOfAvgInc, int maxPERatio, double minRating, double maxRating, double maxMarketCap) {
        this.sellCutOffPerc = sellCutOffPerc;
        this.lowerPriceToLongAvgBuyIn = lowerPriceToLongAvgBuyIn;
        this.higherPriceToLongAvgBuyIn = higherPriceToLongAvgBuyIn;
        this.timeFrameForUpwardLongAvg = timeFrameForUpwardLongAvg;
        this.aboveAvgRatingPricePerc = aboveAvgRatingPricePerc;
        this.timeFrameForUpwardShortPrice = timeFrameForUpwardShortPrice;
        this.timeFrameForOsilator = timeFrameForOsilator;
        this.maxRSI = maxRSI;
        this.minMarketCap = minMarketCap;
        this.maxMarketCap = maxMarketCap;
        this.minRateOfAvgInc = minRateOfAvgInc;
        this.maxPERatio = maxPERatio;
        this.minRating = minRating;
        this.maxRating = maxRating;
        timeFrames = new HashMap<>();
        key = GenerateKey(sellCutOffPerc, lowerPriceToLongAvgBuyIn, higherPriceToLongAvgBuyIn, timeFrameForUpwardLongAvg, aboveAvgRatingPricePerc, timeFrameForUpwardShortPrice, timeFrameForOsilator, maxRSI, minMarketCap, longMovingAvgTime, minRateOfAvgInc, maxPERatio, minRating, maxRating, maxMarketCap);
    }

    public double getSellCutOffPerc() {
        return sellCutOffPerc;
    }

    public static String GenerateKey(double sellCutOffPerc, double lowerPriceToLongAvgBuyIn, double higherPriceToLongAvgBuyIn, int timeFrameForUpwardLongAvg, double aboveAvgRatingPricePerc, int timeFrameForUpwardShortPrice, int timeFrameForOsilator, double maxRSI, double minMarketCap, int longMovingAvgTime, double minRatesOfAvgInc, int maxPERatio, double minRating, double maxRating, double maxMarketCap) {
        return sellCutOffPerc + "," + lowerPriceToLongAvgBuyIn + "," + higherPriceToLongAvgBuyIn + "," + timeFrameForUpwardLongAvg + "," + aboveAvgRatingPricePerc + "," + timeFrameForUpwardShortPrice + "," + timeFrameForOsilator + "," + maxRSI + "," + minMarketCap + "," + longMovingAvgTime + "," + minRatesOfAvgInc + "," + maxPERatio + "," + minRating + "," + maxRating + "," + maxMarketCap;
    }

    public void AddTimeFrame(StocksTradeTimeFrame timeFrame) {
        timeFrames.put(timeFrame.key, timeFrame);
    }

    public Double todayStocksEval(List<Double> movingAvg, int daysFromStart, Stock stock) {
        if (daysFromStart - timeFrameForUpwardLongAvg * 2 < 0 ||
                Objects.isNull(movingAvg.get(daysFromStart - timeFrameForUpwardLongAvg * 2))) {
            System.err.println("stock doesnt have enoght data for avgs " + stock.ticker_symbol());
            return 0.0;
        }

        return StatsCalculator.calculateSlidingAvg(movingAvg, daysFromStart, timeFrameForUpwardLongAvg, daysFromStart + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockMovingAvg") /
                StatsCalculator.calculateSlidingAvg(movingAvg, daysFromStart - timeFrameForUpwardLongAvg, timeFrameForUpwardLongAvg, (daysFromStart - timeFrameForUpwardLongAvg) + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockMovingAvg");
    }

    public boolean stocksfilter(List<Double> price, List<Double> movingAvg, int daysFromStart, List<Double> avgs, Stock stock, List<Double> epss, boolean includeSharpness, List<Double> rating, List<Double> caps) {
        if (daysFromStart - timeFrameForUpwardLongAvg * 2 < 0) return false;
        if (Objects.isNull(movingAvg.get(daysFromStart - timeFrameForUpwardLongAvg * 2))) return false;

        var baseFilter = //stock.final_assessment() > minBaseAssesment &&
                caps.get(daysFromStart) > minMarketCap && caps.get(daysFromStart) < maxMarketCap &&

                        //current price in range of moving avg
                        price.get(daysFromStart) > (movingAvg.get(daysFromStart) * lowerPriceToLongAvgBuyIn) &&
                        price.get(daysFromStart) < (movingAvg.get(daysFromStart) * higherPriceToLongAvgBuyIn) &&

                        rating.get(daysFromStart) > minRating && rating.get(daysFromStart) < maxRating &&
                        //last day the stock went up
                        //price.get(daysFromStart) > price.get(daysFromStart - 1) &&
                        //price.get(daysFromStart) < avgs.get(daysFromStart) * aboveAvgRatingPricePerc &&
                        price.get(daysFromStart) / epss.get(daysFromStart) < maxPERatio;
        if (includeSharpness) {
            baseFilter = baseFilter && StatsCalculator.calculateSlidingAvg(movingAvg, daysFromStart, timeFrameForUpwardLongAvg, daysFromStart + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockMovingAvg") > minRateOfAvgInc *
                    StatsCalculator.calculateSlidingAvg(movingAvg, daysFromStart - timeFrameForUpwardLongAvg, timeFrameForUpwardLongAvg, (daysFromStart - timeFrameForUpwardLongAvg) + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockMovingAvg");

        }

        //has no impact becuase all ready good
                /*StatsCalculator.calculateSlidingAvg(price, daysFromStart, timeFrameForUpwardShortPrice, daysFromStart + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockPrice") >
                        StatsCalculator.calculateSlidingAvg(price, daysFromStart - timeFrameForUpwardShortPrice, timeFrameForUpwardShortPrice, (daysFromStart - timeFrameForUpwardLongAvg) + "," + timeFrameForUpwardLongAvg + stock.ticker_symbol() + " stockMovingAvg");*/

        if (!baseFilter) {
            return false;
        }

// Generate keys once and reuse
        var last50Key = stock.ticker_symbol() + "," + (daysFromStart - timeFrameForOsilator) + "," + daysFromStart;
        var last100Key = stock.ticker_symbol() + "," + (daysFromStart - 2 * timeFrameForOsilator) + "," + (daysFromStart - timeFrameForOsilator);

// Compute or fetch values for the last 50 days
        int lowestLast50, highestLast50;
        if (StatsCalculator.preComputedLows.containsKey(last50Key) && StatsCalculator.preComputedHighs.containsKey(last50Key)) {
            lowestLast50 = StatsCalculator.preComputedLows.get(last50Key);
            highestLast50 = StatsCalculator.preComputedHighs.get(last50Key);
        } else {
            int[] result = StatsCalculator.findLowestAndHighest(price, daysFromStart - timeFrameForOsilator, daysFromStart);
            lowestLast50 = result[0];
            highestLast50 = result[1];
            StatsCalculator.preComputedLows.put(last50Key, lowestLast50);
            StatsCalculator.preComputedHighs.put(last50Key, highestLast50);
        }

// Compute or fetch values for the last 100 days
        int lowestLast100, highestLast100;
        if (StatsCalculator.preComputedLows.containsKey(last100Key) && StatsCalculator.preComputedHighs.containsKey(last100Key)) {
            lowestLast100 = StatsCalculator.preComputedLows.get(last100Key);
            highestLast100 = StatsCalculator.preComputedHighs.get(last100Key);
        } else {
            int[] result = StatsCalculator.findLowestAndHighest(price, daysFromStart - 2 * timeFrameForOsilator, daysFromStart - timeFrameForOsilator);
            lowestLast100 = result[0];
            highestLast100 = result[1];
            StatsCalculator.preComputedLows.put(last100Key, lowestLast100);
            StatsCalculator.preComputedHighs.put(last100Key, highestLast100);
        }


        baseFilter = lowestLast50 > lowestLast100 && highestLast100 < highestLast50 &&
                lowestLast50 + lowestLast100 < highestLast100 + highestLast50 &&
                price.get(lowestLast50) > price.get(lowestLast100) &&
                price.get(highestLast100) < price.get(highestLast50);
        return baseFilter;
        /*var rsi = StatsCalculator.calculateRSI(price.subList(daysFromStart - 14, daysFromStart + 1).stream().toList(), stock.ticker_symbol() + daysFromStart);
        return rsi < maxRSI;*/

    }

    public double getAvg() {
        return !timeFrames.isEmpty() ? timeFrames.values().stream().mapToDouble(StocksTradeTimeFrame::getAvg).average().getAsDouble() : 0;
    }

    public double calculateSimulationScore() {
        double totalExcessReturn = 0.0;
        int tradeCount = 0;
        double dailyRiskFreeRate = Math.pow(1 + 0.12, 1.0 / 252) - 1;

        for (var tradeFrame : timeFrames.values()) {
            for (var trade : tradeFrame.Trades()) {
                double tradeReturn = trade.getLastGained() * 100;
                double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDays()) - 1) * 100;
                totalExcessReturn += (tradeReturn - riskFreeReturn);
                tradeCount++;
            }
        }
        return totalExcessReturn / tradeCount;
    }


    @Override
    public String toString() {
        StringBuilder string = new StringBuilder("sellCutOffPerc,lowerPriceToLongAvgBuyIn,higherPriceToLongAvgBuyIn,timeFrameForUpwardLongAvg,aboveAvgRatingPricePerc,timeFrameForUpwardShortPrice,timeFrameForOsilator,maxRSI,minMarketCap,minBaseAssesment,minRatesOfAvgInc,maxPERatio,maxMarketCap" + "\n" + key + " avg: " + calculateSimulationScore() + " , total trades: " + timeFrames.values().stream().mapToInt(timeFrame -> timeFrame.Trades().size()).sum() + "\n\n");
        var printFrames = timeFrames.values().stream().sorted(Comparator.comparingDouble(StocksTradeTimeFrame::getAvg)).toList();
        for (var timeFrame : printFrames) {
            string.append(timeFrame.toString()).append("\n\n");
        }
        string.append("\n");
        return string.toString();
    }
}
