package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class ParamOptimizerTest {

    private static final double PROFITABLE_TRADE_SCORE_MIN = 0.0;
    private static final double RANDOM_RADIUS = 0.2;

    @Test
    public void testSimulationScoreReflectsGains() {
        SimulationParams params = new SimulationParams(0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1);
        Simulation sim = new Simulation(params);
        
        StocksTradeTimeFrame tf = new StocksTradeTimeFrame(100, 100, 100);
        // Add trades with consistent gains
        tf.AddRow(new StockTrade("TEST", 0.1, 0, 10, 1.0, 1000.0, "2023-01-01"));
        tf.AddRow(new StockTrade("TEST2", 0.2, 0, 20, 1.0, 1000.0, "2023-01-01"));
        
        sim.AddTimeFrame(tf);
        
        double score = sim.calculateSimulationScore();
        assertTrue(score > PROFITABLE_TRADE_SCORE_MIN, 
            String.format("Risk-adjusted score %.2f should be positive for profitable trades", score));
    }

    @Test
    public void testRandomizeRespectsLogicalBounds() {
        SimulationParams center = new SimulationParams(0.95, 0.9, 1.1, 50, 1.05, 20, 100, 70.0, 1000.0, 140, 1.1, 25, 3.75, 4.6, 2750.0, 0.05,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1);
        
        SimulationRangeConfig config = new SimulationRangeConfig();
        ParamOptimizer optimizer = new ParamOptimizer(config);
        
        for (int i = 0; i < 100; i++) {
            SimulationParams rand = optimizer.randomize(center, RANDOM_RADIUS);
            
            // Core trading logic bounds (mostly 0.1 - 3.0)
            assertWithin(rand.sellCutOffPerc(), 0.1, 2.0, "sellCutOffPerc");
            assertWithin(rand.lowerPriceToLongAvgBuyIn(), 0.1, 2.0, "lowerPriceToLongAvgBuyIn");
            assertWithin(rand.higherPriceToLongAvgBuyIn(), 0.1, 3.0, "higherPriceToLongAvgBuyIn");
            
            // Timeframe bounds
            assertWithin(rand.timeFrameForUpwardLongAvg(), 2, 500, "timeFrameForUpwardLongAvg");
            assertWithin(rand.timeFrameForUpwardShortPrice(), 1, 100, "timeFrameForUpwardShortPrice");
            
            // Market bounds
            assertWithin(rand.maxRSI(), 0.0, 100.0, "maxRSI");
            assertTrue(rand.minMarketCap() < rand.maxMarketCap(), "minMarketCap should be < maxMarketCap");
            assertTrue(rand.minRating() < rand.maxRating(), "minRating should be < maxRating");
            assertWithin(rand.minRating(), 0.0, 5.0, "minRating");
            assertWithin(rand.maxRating(), 0.0, 5.0, "maxRating");
        }
    }

    private void assertWithin(double val, double min, double max, String field) {
        assertTrue(val >= min && val <= max, 
            String.format("Field %s: Value %.2f not within logical bounds [%.2f, %.2f]", field, val, min, max));
    }
}
