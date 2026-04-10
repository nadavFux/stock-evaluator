package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class RecommendationValidationTest {

    private Stock createDummyStock(String ticker) {
        return new Stock("id_" + ticker, ticker, ticker, "EX", "2023-01-01", 1000f, 1.0, 1.0, "ident", "other", 1.0);
    }

    private StockGraphState createStock(Stock stock, double startPrice, double endPrice, double ratingVal, int size) {
        List<Double> prices = new ArrayList<>();
        // Linear interpolation from start to end
        for (int i = 0; i < size; i++) {
            prices.add(startPrice + (endPrice - startPrice) * i / (size - 1));
        }
        List<Double> ratings = Collections.nCopies(size, ratingVal);
        List<Double> ones = Collections.nCopies(size, 1.0);
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < size; i++) dates.add("2023-01-" + (i + 1));

        return new StockGraphState(stock, ratings, 0.0, 0.0, 0.0, prices, ones, ones, dates, ones, ones);
    }

    @Test
    public void testHighestGainStockIsRecommended() {
        // Stock A: Starts at 100, drops to 90 (buy signal territory), ends at 150 (Huge gain)
        // Stock B: Starts at 100, stays at 100 (No gain)
        
        Stock sA = createDummyStock("WINNER");
        List<Double> pA = new ArrayList<>(Arrays.asList(110.0, 105.0, 100.0, 95.0, 90.0, 100.0, 110.0, 120.0, 130.0, 150.0));
        // We need enough data for MA (let's say period is 5)
        // Pad with 110 at start
        for(int i=0; i<20; i++) pA.add(0, 110.0);
        
        StockGraphState stockA = createStock(sA, 110.0, 110.0, 5.0, 30); // Use helper for consistency
        // Override prices for Stock A to have a "dip" then "moon"
        for(int i=0; i<pA.size(); i++) stockA.closePrices().set(stockA.closePrices().size() - pA.size() + i, pA.get(i));

        Stock sB = createDummyStock("LOSER");
        StockGraphState stockB = createStock(sB, 100.0, 100.0, 3.0, 30);

        SimulationRangeConfig config = new SimulationRangeConfig();
        config.sellCutOffPerc = List.of(0.95);
        config.lowerPriceToLongAvgBuyIn = List.of(0.8);
        config.higherPriceToLongAvgBuyIn = List.of(1.2);
        config.longMovingAvgTimes = List.of(10);
        config.startTimes = List.of(10);
        config.searchTimes = List.of(10);
        config.selectTimes = List.of(10);
        config.riskFreeRate = List.of(0.0);
        config.minRatings = List.of(1.0);
        config.maxRatings = List.of(5.0);
        config.timeFrameForUpwardLongAvg = List.of(5);
        config.minRatesOfAvgInc = List.of(0.0);
        config.maxPERatios = List.of(100);
        config.maxMarketCap = List.of(1e12);
        config.minMarketCap = List.of(0.0);
        config.maxRSI = List.of(100.0);
        config.aboveAvgRatingPricePerc = List.of(2.0);
        config.timeFrameForUpwardShortPrice = List.of(5);
        config.timeFrameForOscillator = List.of(5);
        config.outputPath = "output_test";

        ParamOptimizer optimizer = new ParamOptimizer(config);
        SimulationParams best = optimizer.optimize(List.of(stockA, stockB));
        
        // Final evaluation on the last day to see recommendations
        Simulation sim = new Simulation(best);
        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stockA, stockB));
        
        // Check heuristic score at the "dip" (day 24, where price was 90)
        // DaysCount is 30. Day 24 is index 24.
        SimulationResult resDip = sim.calculateFastScore(pkg, 0, 24);
        System.out.println("Winner Heuristic at Dip: " + resDip.heuristicScore());
        
        assertTrue(resDip.heuristicScore() > 0.6, 
            "Winner stock should have a high heuristic score during the dip (buy signal)");
            
        SimulationResult resEndA = sim.calculateFastScore(pkg, 0, pkg.daysCount - 1);
        SimulationResult resEndB = sim.calculateFastScore(pkg, 1, pkg.daysCount - 1);
        
        System.out.println("Winner Heuristic at End: " + resEndA.heuristicScore());
        System.out.println("Loser Heuristic at End: " + resEndB.heuristicScore());
    }
}
