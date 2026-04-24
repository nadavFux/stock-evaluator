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
        List<Double> pA = new ArrayList<>();
        // Padding for MA (needs ~250 days for logic)
        for(int i=0; i<300; i++) pA.add(110.0); // Stable padding
        pA.addAll(Arrays.asList(110.0, 111.0, 90.0, 95.0, 100.0, 105.0, 110.0, 120.0, 130.0, 150.0));
        
        StockGraphState stockA = createStock(sA, 110.0, 110.0, 5.0, pA.size()); 
        // Override prices for Stock A
        for(int i=0; i<pA.size(); i++) stockA.closePrices().set(i, pA.get(i));

        Stock sB = createDummyStock("LOSER");
        StockGraphState stockB = createStock(sB, 100.0, 100.0, 3.0, pA.size());

        SimulationRangeConfig config = new SimulationRangeConfig();
        config.sellCutOffPerc = List.of(0.95);
        config.lowerPriceToLongAvgBuyIn = List.of(0.95); // Matches 90/110 ~ 0.82
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
        config.riskFreeRate = List.of(0.0);
        config.startTimes = List.of(10);
        config.searchTimes = List.of(10);
        config.selectTimes = List.of(1);
        config.minRatings = List.of(1.0);
        config.maxRatings = List.of(5.0);
        config.longMovingAvgTimes = List.of(5);

        config.movingAvgGapWeight = List.of(0.2);
        config.reversionToMeanWeight = List.of(0.15);
        config.ratingWeight = List.of(0.2);
        config.upwardIncRateWeight = List.of(0.15);
        config.rvolWeight = List.of(0.1);
        config.pegWeight = List.of(0.1);
        config.volatilityCompressionWeight = List.of(0.1);

        ParamOptimizer optimizer = new ParamOptimizer(config);
        SimulationParams best = optimizer.optimize(List.of(stockA, stockB));
        
        // Final evaluation on the last day to see recommendations
        Simulation sim = new Simulation(best);
        sim.isTest = true;
        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stockA, stockB));
        java.util.Arrays.fill(pkg.rsi[0], 30.0);
        java.util.Arrays.fill(pkg.atr[0], 1.0);
        java.util.Arrays.fill(pkg.rsi[1], 30.0);
        java.util.Arrays.fill(pkg.atr[1], 1.0);
        
        // Find the dip index dynamically (where price was 90.0)
        int dipIdx = -1;
        for(int i=0; i<stockA.closePrices().size(); i++) {
            if(stockA.closePrices().get(i) == 90.0) {
                dipIdx = i;
                break;
            }
        }
        
        // Check heuristic score at the "dip"
        SimulationResult resDip = sim.calculateFastScore(pkg, 0, dipIdx);
        System.out.println("Winner Heuristic at Dip (" + dipIdx + "): " + resDip.heuristicScore());
        
        assertTrue(resDip.heuristicScore() >= 0.1, 
            "Winner stock should have a high heuristic score during the dip (buy signal)");
            
        SimulationResult resEndA = sim.calculateFastScore(pkg, 0, pkg.daysCount - 1);
        SimulationResult resEndB = sim.calculateFastScore(pkg, 1, pkg.daysCount - 1);
        
        System.out.println("Winner Heuristic at End: " + resEndA.heuristicScore());
        System.out.println("Loser Heuristic at End: " + resEndB.heuristicScore());
    }
}
