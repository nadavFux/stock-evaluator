package com.stock.analyzer.service;

import com.stock.analyzer.model.dto.SimulationResult;
import com.stock.analyzer.model.dto.StockCheckResult;
import com.stock.analyzer.model.dto.StockGraphState;
import com.stock.analyzer.model.dto.StockTrade;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.util.StatsCalculator;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.core.SimulationConfig;

import java.util.List;

public class DailyAnalysisStrategy implements StockAnalysisStrategy {
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

    public SimulationResult simulate(StockGraphState stock) {
        int longMovingAvgTime = 140;
        double currSellCutOffPerc = 0.93;
        double currLowerPriceToLongAvgBuyIn = 0.92; // lower to higher
        double currHigherPriceToLongAvgBuyIn = 1.02; // higher to lower
        int currTimeFrameForUpwardLongAvg = 40;
        int currTimeFrameForOsilator = 110;
        int currPERatio = 25; // higher to lower lower is better
        double currAboveAvgRatingPricePerc = 1; // higher to lower, what ever
        int currTimeFrameForUpwardShortPrice = 1; // whatever
        double currMaxRSI = 100; // has negative impact
        double currMinMarketCap = 1300;
        double currMaxMarketCap = 2750;
        double currMinRateOfAvgInc = 1.1;// lower to higher big barrier and most importent 1.15-1.25 is ideal
        double currMinRating = 3.75;
        double currMaxRating = 4.6;
        // System.out.println("startednewstock " + stock.stock().ticker_symbol());

        var movingAvg = StatsCalculator.MovingAvg(stock, longMovingAvgTime);

        if (movingAvg.size() - 1 <= currTimeFrameForOsilator * 2) {
            return null;
        }

        var currTimeFrameKey = StocksTradeTimeFrame.GenerateKey(1, 0, 1);
        var currConfig = new SimulationConfig(
                currSellCutOffPerc, currLowerPriceToLongAvgBuyIn, currHigherPriceToLongAvgBuyIn,
                currTimeFrameForUpwardLongAvg,
                currAboveAvgRatingPricePerc, currTimeFrameForUpwardShortPrice, currTimeFrameForOsilator, currMaxRSI,
                currMinMarketCap, longMovingAvgTime, currMinRateOfAvgInc, currPERatio, currMinRating, currMaxRating,
                currMaxMarketCap);
        var currSimulation = StatsCalculator.getOrAddSimulation(currConfig);

        StocksTradeTimeFrame currTimeFrame;
        var timeFrameOptional = currSimulation.timeFrames.getOrDefault(currTimeFrameKey, null);
        if (timeFrameOptional != null) {
            currTimeFrame = timeFrameOptional;
        } else {
            currTimeFrame = new StocksTradeTimeFrame(1, 0, 1);
        }

        var shouldAddTimeFrame = simulateTimeFrameForStock(
                stock,
                movingAvg,
                currSimulation,
                currTimeFrame);
        if (shouldAddTimeFrame) {

            if (!currSimulation.timeFrames.containsKey(currTimeFrame.key)) {
                currSimulation.AddTimeFrame(currTimeFrame);
            }
        }

        return new SimulationResult(currSimulation.todayStocksEval(movingAvg, movingAvg.size() - 1, stock.stock()),
                0.0);
    }

    public boolean simulateTimeFrameForStock(StockGraphState stock, List<Double> movingAvg, Simulation currSimulation,
            StocksTradeTimeFrame timeFrame) {
        var movingAvgSize = movingAvg.size();
        var timeStart = movingAvgSize - 1;
        if (timeStart < 0) {
            return false;
        }

        if (movingAvg.get(timeStart) != null) {
            if (currSimulation.stocksfilter(stock.closePrices(), movingAvg, timeStart, stock.avgs(), stock.stock(),
                    stock.epss(), true, stock.rating(), stock.caps())) {
                System.out.println("should buy" + stock.stock().ticker_symbol());
                var startingPriceOverAvg = stock.closePrices().get(timeStart) / movingAvg.get(timeStart);

                var cutOff = startingPriceOverAvg * currSimulation.getSellCutOffPerc();
                var lowerBound = Math.max((movingAvg.get(timeStart) * cutOff) - stock.closePrices().get(timeStart),
                        (0.0));
                timeFrame.AddRow(new StockTrade(stock.stock().ticker_symbol(),
                        ((lowerBound / stock.closePrices().get(timeStart))),
                        1, 0, startingPriceOverAvg,
                        (double) stock.stock().market_cap_before_filing_date(),
                        stock.dates().isEmpty() ? "" : stock.dates().get(timeStart),
                        currSimulation.todayStocksEval(movingAvg, timeStart, stock.stock()) + " rating "
                                + stock.rating().get(timeStart)));

                return true;
            }
        }

        return false;
    }
}
