package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.SimulationResult;
import com.stock.analyzer.model.dto.StockCheckResult;
import com.stock.analyzer.model.dto.StockGraphState;
import com.stock.analyzer.model.dto.StockTrade;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.util.StatsCalculator;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.core.SimulationConfig;

import java.util.Arrays;
import java.util.List;

public class DeepSimulationStrategy implements StockAnalysisStrategy {
    @Override
    public StockCheckResult checkStockData(StockGraphState stock) {
        try {
            return new StockCheckResult(stock, simulate(stock)); // : null;
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error check stock data" + e.getMessage());
            return null;
        }
    }

    private Double michuStocksEval(StockGraphState stock) {
        return 1.0;
    }

    public SimulationResult simulate(StockGraphState stock) {
        /*
         * int[] longMovingAvgTimes = {140};
         * double[] sellCutOffPerc = {0.96};
         * double[] lowerPriceToLongAvgBuyIn = {0.92}; // lower to higher
         * double[] higherPriceToLongAvgBuyIn = {1}; // higher to lower
         * int[] timeFrameForUpwardLongAvg = {40};
         * int[] timeFrameForOsilator = {185};
         * int[] maxPErations = {25}; // higher to lower lower is better
         * double[] aboveAvgRatingPricePerc = {1}; // higher to lower, what ever
         * int[] timeFrameForUpwardShortPrice = {1}; // whatever
         * double[] maxRSI = {100}; // has negative impact, removed
         * double[] minMarketCap = {750};
         * double[] maxMarketCap = {2_000.0};
         * double[] minRatesOfAvgInc = {1.1};// lower to higher big barrier and most
         * importent 1.15-1.25 is ideal
         * double[] minRatings = {4};// lower to higher
         * double[] maxRatings = {4.8};// higher to lower
         */

        /*
         * int[] startTimes = {33};
         * int[] selectTimes = {33};
         * int[] searchTimes = {0};
         */

        // int[] startTimes = {35, 65, 95, 125, 155, 185, 215, 245, 275, 305, 335, 365,
        // 395, 425, 455, 485};
        int[] startTimes = { 50, 80, 110, 140, 170, 200, 230, 260, 290, 320, 350, 380, 410, 440, 470 };
        int[] selectTimes = { 30 };
        int[] searchTimes = { 30, 80, 130, 180 };

        int[] longMovingAvgTimes = { 140 };
        double[] sellCutOffPerc = { 0.93 };
        double[] lowerPriceToLongAvgBuyIn = { 0.92 }; // lower to higher
        double[] higherPriceToLongAvgBuyIn = { 1.02 }; // higher to lower
        int[] timeFrameForUpwardLongAvg = { 40 };
        int[] timeFrameForOsilator = { 110 };
        int[] maxPErations = { 25 }; // higher to lower lower is better
        double[] aboveAvgRatingPricePerc = { 1 }; // higher to lower, what ever
        int[] timeFrameForUpwardShortPrice = { 1 }; // whatever
        double[] maxRSI = { 100 }; // has negative impact, removed
        double[] minMarketCap = { 1300 };
        double[] maxMarketCap = { 2750 };
        double[] minRatesOfAvgInc = { 1.1 };// lower to higher big barrier and most importent 1.15-1.25 is ideal
        double[] minRatings = { 3.75 };// lower to higher
        double[] maxRatings = { 4.6 };// higher to lower
        // System.out.println("startednewstock " + stock.stock().ticker_symbol());
        for (int longMovingAvgTime : longMovingAvgTimes) {
            var movingAvg = StatsCalculator.MovingAvg(stock, longMovingAvgTime);
            for (int startTime : startTimes) {
                for (int selectTime : selectTimes) {
                    if (startTime >= selectTime) {
                        for (int searchTime : searchTimes) {
                            if (startTime > searchTime) {

                                var currTimeFrameKey = StocksTradeTimeFrame.GenerateKey(startTime, searchTime,
                                        selectTime);

                                for (double currSellCutOffPerc : sellCutOffPerc) {

                                    var foundInLowerLowerPriceToBuy = false;
                                    for (var i = 0; i < lowerPriceToLongAvgBuyIn.length; i++) {
                                        double currLowerPriceToLongAvgBuyIn = lowerPriceToLongAvgBuyIn[i];
                                        if (i == 0 || foundInLowerLowerPriceToBuy) {
                                            // if (currSellCutOffPerc < currLowerPriceToLongAvgBuyIn) {
                                            var foundInHigherHigherPriceToBuy = false;
                                            for (var j = 0; j < higherPriceToLongAvgBuyIn.length; j++) {
                                                double currHigherPriceToLongAvgBuyIn = higherPriceToLongAvgBuyIn[j];
                                                if (j == 0 || foundInHigherHigherPriceToBuy) {
                                                    if (currHigherPriceToLongAvgBuyIn > currLowerPriceToLongAvgBuyIn) {
                                                        for (int currTimeFrameForUpwardLongAvg : timeFrameForUpwardLongAvg) {
                                                            var foundInHigherPERatio = false;
                                                            for (var p = 0; p < maxPErations.length; p++) {
                                                                int currPERatio = maxPErations[p];
                                                                if (p == 0 || foundInHigherPERatio) {

                                                                    var foundInLowerAvgRatingGap = false;
                                                                    for (var k = 0; k < aboveAvgRatingPricePerc.length; k++) {
                                                                        double currAboveAvgRatingPricePerc = aboveAvgRatingPricePerc[k];
                                                                        if (k == 0 || foundInLowerAvgRatingGap) {
                                                                            if (currLowerPriceToLongAvgBuyIn < currHigherPriceToLongAvgBuyIn) {
                                                                                for (int currTimeFrameForUpwardShortPrice : timeFrameForUpwardShortPrice) {
                                                                                    for (int currTimeFrameForOsilator : timeFrameForOsilator) {
                                                                                        if (movingAvg.size()
                                                                                                - startTime > currTimeFrameForOsilator
                                                                                                        * 2) {

                                                                                            var foundInLowerRSI = false;
                                                                                            for (var w = 0; w < maxRSI.length; w++) {
                                                                                                double currMaxRSI = maxRSI[w];
                                                                                                if (w == 0
                                                                                                        || foundInLowerRSI) {
                                                                                                    for (double currMinMarketCap : minMarketCap) {
                                                                                                        for (double currMaxMarketCap : maxMarketCap) {
                                                                                                            if (currMinMarketCap < currMaxMarketCap) {
                                                                                                                var foundInLowerIncRateForAvg = false;
                                                                                                                for (var s = 0; s < minRatesOfAvgInc.length; s++) {
                                                                                                                    double currMinRateOfAvgInc = minRatesOfAvgInc[s];
                                                                                                                    if (s == 0
                                                                                                                            || foundInLowerIncRateForAvg) {

                                                                                                                        var foundInLowerMinRating = false;
                                                                                                                        for (var d = 0; d < minRatings.length; d++) {
                                                                                                                            double currMinRating = minRatings[d];
                                                                                                                            if (d == 0
                                                                                                                                    || foundInLowerMinRating) {

                                                                                                                                var foundInHigherMaxRating = false;
                                                                                                                                for (var f = 0; f < maxRatings.length; f++) {
                                                                                                                                    double currMaxRating = maxRatings[f];
                                                                                                                                    if (f == 0
                                                                                                                                            || foundInHigherMaxRating) {
                                                                                                                                        if (currMaxRating > currMinRating) {
                                                                                                                                            var currConfig = new SimulationConfig(
                                                                                                                                                    currSellCutOffPerc,
                                                                                                                                                    currLowerPriceToLongAvgBuyIn,
                                                                                                                                                    currHigherPriceToLongAvgBuyIn,
                                                                                                                                                    currTimeFrameForUpwardLongAvg,
                                                                                                                                                    currAboveAvgRatingPricePerc,
                                                                                                                                                    currTimeFrameForUpwardShortPrice,
                                                                                                                                                    currTimeFrameForOsilator,
                                                                                                                                                    currMaxRSI,
                                                                                                                                                    currMinMarketCap,
                                                                                                                                                    longMovingAvgTime,
                                                                                                                                                    currMinRateOfAvgInc,
                                                                                                                                                    currPERatio,
                                                                                                                                                    currMinRating,
                                                                                                                                                    currMaxRating,
                                                                                                                                                    currMaxMarketCap);
                                                                                                                                            var currSimulation = StatsCalculator
                                                                                                                                                    .getOrAddSimulation(
                                                                                                                                                            currConfig);

                                                                                                                                            StocksTradeTimeFrame currTimeFrame;
                                                                                                                                            var timeFrameOptional = currSimulation.timeFrames
                                                                                                                                                    .getOrDefault(
                                                                                                                                                            currTimeFrameKey,
                                                                                                                                                            null);
                                                                                                                                            if (timeFrameOptional != null) {
                                                                                                                                                currTimeFrame = timeFrameOptional;
                                                                                                                                            } else {
                                                                                                                                                currTimeFrame = new StocksTradeTimeFrame(
                                                                                                                                                        startTime,
                                                                                                                                                        searchTime,
                                                                                                                                                        selectTime);
                                                                                                                                            }
                                                                                                                                            var shouldAddTimeFrame = simulateTimeFrameForStock(
                                                                                                                                                    stock,
                                                                                                                                                    startTime,
                                                                                                                                                    searchTime,
                                                                                                                                                    selectTime,
                                                                                                                                                    movingAvg,
                                                                                                                                                    currSimulation,
                                                                                                                                                    currTimeFrame);
                                                                                                                                            if (shouldAddTimeFrame) {
                                                                                                                                                foundInLowerLowerPriceToBuy = true;
                                                                                                                                                foundInHigherHigherPriceToBuy = true;
                                                                                                                                                foundInLowerAvgRatingGap = true;
                                                                                                                                                foundInHigherPERatio = true;
                                                                                                                                                foundInLowerRSI = true;
                                                                                                                                                foundInLowerIncRateForAvg = true;
                                                                                                                                                foundInLowerMinRating = true;
                                                                                                                                                foundInHigherMaxRating = true;
                                                                                                                                                if (!currSimulation.timeFrames
                                                                                                                                                        .containsKey(
                                                                                                                                                                currTimeFrame.key)) {
                                                                                                                                                    currSimulation
                                                                                                                                                            .AddTimeFrame(
                                                                                                                                                                    currTimeFrame);
                                                                                                                                                }
                                                                                                                                            }
                                                                                                                                        }
                                                                                                                                    }
                                                                                                                                }
                                                                                                                            }
                                                                                                                        }
                                                                                                                    }
                                                                                                                }
                                                                                                            }
                                                                                                        }
                                                                                                    }
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                                // }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new SimulationResult(0.0, 0.0);
    }

    public boolean simulateTimeFrameForStock(StockGraphState stock, int daysToGoBack, int searchTime, int selectTime,
            List<Double> movingAvg, Simulation currSimulation, StocksTradeTimeFrame timeFrame) {
        var shouldBuy = false;
        var movingAvgSize = movingAvg.size();
        var timeStart = movingAvgSize - daysToGoBack;
        if (timeStart < 0) {
            return false;
        }

        for (int i = timeStart; i < timeStart + selectTime; i++) {
            try {
                if (movingAvg.get(i) != null) {
                    if (currSimulation.stocksfilter(stock.closePrices(), movingAvg, i, stock.avgs(), stock.stock(),
                            stock.epss(), true, stock.rating(), stock.caps())) {
                        shouldBuy = true;
                        System.out.println("found " + stock.stock().ticker_symbol() + " at  " + stock.dates().get(i));
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println(
                        "Error during select simulation at index " + i + ": " + Arrays.toString(e.getStackTrace()));
            }
        }
        if (!shouldBuy) {
            return false;
        }
        for (int i = timeStart; i < timeStart + searchTime; i++) {
            try {
                if (movingAvg.get(i) != null) {
                    var startingPriceOverAvg = stock.closePrices().get(i) / movingAvg.get(i);
                    if (currSimulation.stocksfilter(stock.closePrices(), movingAvg, i, stock.avgs(), stock.stock(),
                            stock.epss(), true, stock.rating(), stock.caps())) {
                        var cutOff = startingPriceOverAvg * currSimulation.getSellCutOffPerc();
                        var found = false;
                        for (int j = 1; j < timeStart + searchTime - i; j++) {
                            if (stock.closePrices().get(i + j) < (movingAvg.get(i + j) * cutOff)) {
                                var lowerBound = Math.max((movingAvg.get(i + j) * cutOff) - stock.closePrices().get(i),
                                        (stock.closePrices().get(i + j) - stock.closePrices().get(i)));

                                timeFrame.AddRow(new StockTrade(stock.stock().ticker_symbol(),
                                        ((lowerBound / stock.closePrices().get(i))),
                                        daysToGoBack - i + timeStart, j, startingPriceOverAvg,
                                        (double) stock.stock().market_cap_before_filing_date(),
                                        stock.dates().isEmpty() ? "" : stock.dates().get(i)));
                                found = true;
                                i += j;
                                break;
                            }
                        }
                        if (!found) {
                            timeFrame.AddRow(new StockTrade(stock.stock().ticker_symbol(),
                                    (((stock.closePrices().get(timeStart + searchTime) - stock.closePrices().get(i))
                                            / stock.closePrices().get(i))),
                                    daysToGoBack - i + timeStart, searchTime,
                                    stock.closePrices().get(i) / movingAvg.get(i),
                                    (double) stock.stock().market_cap_before_filing_date(),
                                    stock.dates().isEmpty() ? "" : stock.dates().get(i)));
                            i = movingAvgSize;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error during simulation at index " + i + ": " + e.getMessage());
            }
        }
        return true;
    }
}
