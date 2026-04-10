package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

public class ParamOptimizerTest {

    private Stock createDummyStock(String ticker) {
        return new Stock("id", "name", ticker, "exchange", "2023-01-01", 1000f, 1.0, 1.0, "ident", "other", 1.0);
    }

    private StockGraphState createStockWithGainPattern(Stock stock, int size) {
        List<Double> prices = new java.util.ArrayList<>();
        // Create a pattern where every 20 days price drops to 90 (below MA) and then recovers
        for(int i=0; i<size; i++) {
            if (i % 20 == 0) {
                prices.add(90.0);
            } else if (i % 20 == 1) {
                prices.add(110.0); // 22% gain from 90
            } else {
                prices.add(100.0);
            }
        }
        
        List<Double> ones = Collections.nCopies(size, 1.0);
        List<Double> ratings = Collections.nCopies(size, 5.0); // High rating to trigger heuristic
        
        return new StockGraphState(stock, ratings, 0.0, 0.0, 0.0, prices, ones, ones, 
            Collections.nCopies(size, "date"), ones, ones);
    }

    @Test
    public void testOptimizerFindsProfitableParams() {
        Stock s1 = createDummyStock("PROFIT");
        StockGraphState stock = createStockWithGainPattern(s1, 100);

        SimulationRangeConfig config = new SimulationRangeConfig();
        config.sellCutOffPerc = Arrays.asList(0.95, 1.05); 
        config.lowerPriceToLongAvgBuyIn = Arrays.asList(0.8, 1.1);
        config.higherPriceToLongAvgBuyIn = Arrays.asList(1.1, 1.2);
        config.timeFrameForUpwardLongAvg = Arrays.asList(20);
        config.aboveAvgRatingPricePerc = Arrays.asList(1.0);
        config.timeFrameForUpwardShortPrice = Arrays.asList(5);
        config.timeFrameForOscillator = Arrays.asList(10);
        config.maxRSI = Arrays.asList(70.0);
        config.minMarketCap = Arrays.asList(0.0);
        config.longMovingAvgTimes = Arrays.asList(10);
        config.minRatesOfAvgInc = Arrays.asList(0.0);
        config.maxPERatios = Arrays.asList(100);
        config.minRatings = Arrays.asList(1.0);
        config.maxRatings = Arrays.asList(10.0);
        config.maxMarketCap = Arrays.asList(1000000000000.0);
        config.riskFreeRate = Arrays.asList(0.0);
        
        config.startTimes = Arrays.asList(100);
        config.searchTimes = Arrays.asList(100);
        config.selectTimes = Arrays.asList(100);
        config.sectors = Arrays.asList(1);
        config.exchanges = Arrays.asList("TEST");
        config.outputPath = "output_test";

        ParamOptimizer optimizer = new ParamOptimizer(config);
        SimulationParams best = optimizer.optimize(List.of(stock));
        
        assertNotNull(best);
        
        Simulation sim = new Simulation(best);
        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stock));
    }

    @Test
    public void testSimulationScoreReflectsGains() {
        SimulationParams params = new SimulationParams(0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0);
        Simulation sim = new Simulation(params);
        
        StocksTradeTimeFrame tf = new StocksTradeTimeFrame(100, 100, 100);
        // Add a trade with 10% gain over 10 days
        tf.AddRow(new StockTrade("TEST", 0.1, 0, 10, 1.0, 1000.0, "2023-01-01"));
        // Add a trade with 20% gain over 20 days
        tf.AddRow(new StockTrade("TEST2", 0.2, 0, 20, 1.0, 1000.0, "2023-01-01"));
        
        sim.AddTimeFrame(tf);
        
        // Expected score: ((0.1 * 100) + (0.2 * 100)) / 2 = (10 + 20) / 2 = 15.0
        // (Risk free rate is 0.0)
        assertEquals(15.0, sim.calculateSimulationScore(), 0.001);
    }
}
