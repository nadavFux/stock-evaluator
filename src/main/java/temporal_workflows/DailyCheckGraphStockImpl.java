package temporal_workflows;
/*package temporal_workflows;

import common.DTO.StockCheckResult;
import common.DTO.StockGraphState;
import common.StatsCalculator;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class MichuCheckGraphStockImpl implements CheckGraphStock {
    @Override
    public StockCheckResult checkStockData(StockGraphState stock) {
        try
        {
        var moving150 = StatsCalculator.MovingAvg(stock, 150);
        return michuStocksFilter(stock.closePrices(), moving150, moving150.size() - 1, stock.volumes(), stock.stock().ticker_symbol()) ? new StockCheckResult(stock, michuStocksEval(stock), lastTimeChange(stock, moving150, stock.volumes())) : null;
                } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean michuStocksFilter(List<Double> price, List<Double> avg150, int daysFromStart, List<Double> volumes, String ticker) {
        if (Objects.isNull(avg150.get(daysFromStart - 50))) return false;

        var lowestLast50 = IntStream.range(daysFromStart - 75, price.size()).reduce((i, j) -> price.get(i) < price.get(j) ? i : j).getAsInt();
        var lowestLast150 = IntStream.range(daysFromStart - 150, price.size() - 75).reduce((i, j) -> price.get(i) < price.get(j) ? i : j).getAsInt();
        var highestLast50 = IntStream.range(daysFromStart - 75, price.size()).reduce((i, j) -> price.get(i) > price.get(j) ? i : j).getAsInt();
        var highestLast150 = IntStream.range(daysFromStart - 150, price.size() - 75).reduce((i, j) -> price.get(i) > price.get(j) ? i : j).getAsInt();

        return price.get(daysFromStart) > (avg150.get(daysFromStart) * 1.02) && price.get(daysFromStart) < (avg150.get(daysFromStart) * 1.10) &&

                avg150.subList(daysFromStart - 10, daysFromStart + 1).stream().mapToDouble(Double::doubleValue).average().getAsDouble() >
                        avg150.subList(daysFromStart - 20, daysFromStart - 9).stream().mapToDouble(Double::doubleValue).average().getAsDouble() &&
                price.get(daysFromStart) > price.get(daysFromStart - 1) &&

                ((volumes.get(daysFromStart)) > (volumes.get(daysFromStart - 1)) ||
                        price.subList(daysFromStart - 10, daysFromStart + 1).stream().mapToDouble(Double::doubleValue).average().getAsDouble() >
                                price.subList(daysFromStart - 20, daysFromStart - 10).stream().mapToDouble(Double::doubleValue).average().getAsDouble()) &&

                lowestLast50 + lowestLast150 < highestLast150 + highestLast50 + 10 &&
                price.get(lowestLast50) > price.get(lowestLast150) && price.get(highestLast150) < price.get(highestLast50) &&
                StatsCalculator.calculateRSI(price.subList(daysFromStart - 14, daysFromStart + 1).stream().toList()) < 80;

    }

    private Double michuStocksEval(StockGraphState stock) {
        return (double) -stock.stock().market_cap_before_filing_date();
    }

    public double lastTimeChange(StockGraphState stock, List<Double> moving150, List<Double> volumes) {
        return 0.0;
        /*
        for (int i = moving150.size() - 30; i > 350; i--) {
            if (moving150.get(i) != null) {
                if (michuStocksFilter(stock.closePrices(), moving150, i, volumes, stock.stock().ticker_symbol())) {
                    var cutOff = stock.closePrices().get(i) / moving150.get(i) - 0.06 * (stock.closePrices().get(i) / moving150.get(i));
                    var found = false;
                    for (int j = 1; j < moving150.size() - i; j++) {
                        if (stock.closePrices().get(i + j) < (moving150.get(i + j) * cutOff)) {
                            var lowerBound = Math.max((moving150.get(i + j) * cutOff) - stock.closePrices().get(i), (stock.closePrices().get(i + j) - stock.closePrices().get(i)));

                            StatsCalculator.AddRow(stock.stock().ticker_symbol(),
                                    ((lowerBound / stock.closePrices().get(i))),
                                    moving150.size() - i, j, stock.closePrices().get(i) / moving150.get(i),
                                    (double) stock.stock().market_cap_before_filing_date(), cutOff, stock.stock().final_assessment());
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        StatsCalculator.AddRow(stock.stock().ticker_symbol(),
                                (((stock.closePrices().get(stock.closePrices().size() - 1) - stock.closePrices().get(i)) / stock.closePrices().get(i))),
                                moving150.size() - i, moving150.size() - i, stock.closePrices().get(i) / moving150.get(i),
                                (double) stock.stock().market_cap_before_filing_date(), cutOff, stock.stock().final_assessment());
                    }
                }
            }
        }
        System.out.println("found none");
        return 0;
    }
}*/


import common.DTO.SimulationResult;
import common.DTO.StockCheckResult;
import common.DTO.StockGraphState;
import common.DTO.StockTrade;
import common.Simulation;
import common.StatsCalculator;
import common.StocksTradeTimeFrame;

import java.util.List;

public class DailyCheckGraphStockImpl implements CheckGraphStock {
    @Override
    public StockCheckResult checkStockData(StockGraphState stock) {
        try {
            return new StockCheckResult(stock, simulate(stock)); //: null;
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
        //System.out.println("startednewstock " + stock.stock().ticker_symbol());

        var movingAvg = StatsCalculator.MovingAvg(stock, longMovingAvgTime);


        if (movingAvg.size() - 1 <= currTimeFrameForOsilator * 2) {
            return null;
        }

        var currTimeFrameKey = StocksTradeTimeFrame.GenerateKey(1, 0, 1);
        var currKey = Simulation.GenerateKey(currSellCutOffPerc, currLowerPriceToLongAvgBuyIn, currHigherPriceToLongAvgBuyIn, currTimeFrameForUpwardLongAvg, currAboveAvgRatingPricePerc, currTimeFrameForUpwardShortPrice, currTimeFrameForOsilator, currMaxRSI, currMinMarketCap, longMovingAvgTime, currMinRateOfAvgInc, currPERatio, currMinRating, currMaxRating, currMaxMarketCap);
        Simulation currSimulation;
        var simulationOptional = StatsCalculator.SIMULATIONS.getOrDefault(currKey, null);
        if (simulationOptional != null) {
            currSimulation = simulationOptional;
        } else {
            currSimulation = new Simulation(currSellCutOffPerc, currLowerPriceToLongAvgBuyIn, currHigherPriceToLongAvgBuyIn, currTimeFrameForUpwardLongAvg, currAboveAvgRatingPricePerc, currTimeFrameForUpwardShortPrice, currTimeFrameForOsilator, currMaxRSI, currMinMarketCap, longMovingAvgTime, currMinRateOfAvgInc, currPERatio, currMinRating, currMaxRating, currMaxMarketCap);
            StatsCalculator.AddSimulation(currSimulation);
        }

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
                currTimeFrame
        );
        if (shouldAddTimeFrame) {

            if (!currSimulation.timeFrames.containsKey(currTimeFrame.key)) {
                currSimulation.AddTimeFrame(currTimeFrame);
            }
        }

        return new SimulationResult(currSimulation.todayStocksEval(movingAvg, movingAvg.size() - 1, stock.stock()), 0.0);
    }

    public boolean simulateTimeFrameForStock(StockGraphState stock, List<Double> movingAvg, Simulation currSimulation, StocksTradeTimeFrame timeFrame) {
        var movingAvgSize = movingAvg.size();
        var timeStart = movingAvgSize - 1;
        if (timeStart < 0) {
            return false;
        }

        if (movingAvg.get(timeStart) != null) {
            if (currSimulation.stocksfilter(stock.closePrices(), movingAvg, timeStart, stock.avgs(), stock.stock(), stock.epss(), true, stock.rating(), stock.caps())) {
                System.out.println("should buy" + stock.stock().ticker_symbol());
                var startingPriceOverAvg = stock.closePrices().get(timeStart) / movingAvg.get(timeStart);

                var cutOff = startingPriceOverAvg * currSimulation.getSellCutOffPerc();
                var lowerBound = Math.max((movingAvg.get(timeStart) * cutOff) - stock.closePrices().get(timeStart), (0.0));
                timeFrame.AddRow(new StockTrade(stock.stock().ticker_symbol(),
                        ((lowerBound / stock.closePrices().get(timeStart))),
                        1, 0, startingPriceOverAvg,
                        (double) stock.stock().market_cap_before_filing_date(), stock.dates().isEmpty() ? "" : stock.dates().get(timeStart),
                        currSimulation.todayStocksEval(movingAvg, timeStart, stock.stock()) + " rating " + stock.rating().get(timeStart)));

                return true;
            }
        }

        return false;
    }
}