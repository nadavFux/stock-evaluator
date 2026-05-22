package com.stock.analyzer.core;

import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SimulationTest {

    @Test
    public void testRigorousScoreCalculation() {
        SimulationParams params = createDefaultParams();
        Simulation simulation = new Simulation(params);
        
        // 1. Record trades with known returns and durations
        // Trade 1: 10% gain over 10 days
        // Daily Return = 10% / 10 = 1.0%
        simulation.recordTrade(0.10, 10); 
        
        // Trade 2: 5% gain over 5 days
        // Daily Return = 5% / 5 = 1.0%
        simulation.recordTrade(0.05, 5);
        
        // Total Trades = 2, Avg Daily Return = 1.0%, StdDev = 0.0
        // Sharpe = 1.0 / (0 + 0.01) * sqrt(252) = 100 * 15.87 = 1587
        
        double score = simulation.calculateScore(1); 
        
        assertTrue(score > 1000, "Score should be very high for perfect consistency (got: " + score + ")");
        assertEquals(2, simulation.getTradeCount());
    }

    @Test
    public void testWeightNormalization() {
        // Create params where weights sum to 2.0 instead of 1.0
        SimulationParams params = createDefaultParams().toBuilder()
                .movingAvgGapWeight(1.0)
                .ratingWeight(1.0)
                .reversionToMeanWeight(0.0)
                .upwardIncRateWeight(0.0)
                .rvolWeight(0.0)
                .pegWeight(0.0)
                .volatilityCompressionWeight(0.0)
                .build();
        
        Simulation simulation = new Simulation(params);
        
        // Even though weights sum to 2.0, normalization should ensure heuristic is still in [0, 1] range
        Stock stock = new Stock("1", "Test", "TEST", "NAS", "2023-01-01", 100.0f, 4.5, 1.0, "ID", "Other", 1.0);
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 300; i++) prices.add(100.0);
        
        StockGraphState graphState = new StockGraphState(
            stock, Collections.nCopies(300, 4.5), 90.0, 110.0, 100.0, 
            prices, Collections.nCopies(300, 1000.0),
            Collections.nCopies(300, 100.0),
            Collections.nCopies(300, "2023-01-01"),
            Collections.nCopies(300, 1.0),
            Collections.nCopies(300, 1000000.0)
        );
        
        SimulationDataPackage pkg = new SimulationDataPackage(List.of(graphState));
        double h = simulation.calculateHeuristic(pkg, 0, 290);
        
        assertTrue(h <= 1.0, "Heuristic should be normalized even if weights are un-normalized (got: " + h + ")");
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.85, 1.1, 50, 1.05, 20, 10, 70, 1000, 5, 1.0, 30, 1.0, 5.0, 1000000000, 0.0, 0.1,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }
}
