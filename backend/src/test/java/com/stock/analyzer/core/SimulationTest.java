package com.stock.analyzer.core;

import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SimulationTest {

    private static final double MIN_HEURISTIC_THRESHOLD = 0.1;

    private Stock createDummyStock(String ticker) {
        return new Stock("id", "name", ticker, "exchange", "2023-01-01", 1000f, 1.0, 1.0, "ident", "other", 1.0);
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.85, 1.1, 50, 1.05, 20, 10, 70, 1000, 5, 1.0, 30, 1.0, 5.0, 1000000000, 0.05, 0.65,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }

    @Test
    public void testHeuristicScoreCalculation() {
        SimulationParams params = createDefaultParams();
        Simulation simulation = new Simulation(params);
        simulation.isTest = true;
        
        Stock s1 = createDummyStock("AAPL");
        int size = 1200; 
        List<Double> prices = new java.util.ArrayList<>();
        for(int i=0; i<size; i++) prices.add(100.0);
        
        // Final 100 days slightly rising
        for(int i=size-100; i<size; i++) prices.set(i, 110.0 + (i - (size-100)) * 0.1);
        
        int dipIdx = size - 10;
        prices.set(dipIdx, 95.0); // The dip
        prices.set(dipIdx - 1, 120.0); // Pass trend filter
        
        List<Double> ratings = new java.util.ArrayList<>();
        for(int i=0; i<size; i++) ratings.add(5.0); 
        
        List<Double> volumes = new java.util.ArrayList<>();
        for(int i=0; i<size; i++) volumes.add(1000.0);
        volumes.set(dipIdx, 5000.0); 
        
        StockGraphState stock = new StockGraphState(s1, ratings, 0.0, 0.0, 0.0, prices, volumes, prices, 
            java.util.Collections.nCopies(size, "date"), prices, prices);

        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stock));
        pkg.rsi[0][dipIdx] = 30.0;
        pkg.atr[0][dipIdx] = 1.0;
        
        SimulationResult res = simulation.calculateFastScore(pkg, 0, dipIdx);
        
        assertTrue(res.heuristicScore() >= MIN_HEURISTIC_THRESHOLD, 
            String.format("Heuristic score %.2f should be >= threshold %.2f", res.heuristicScore(), MIN_HEURISTIC_THRESHOLD));
    }

    @Test
    public void testSimulationScoreReflectsSharpe() {
        SimulationParams params = createDefaultParams();
        Simulation simulation = new Simulation(params);
        
        // Setup consistent gains (Low volatility = High Sharpe)
        StocksTradeTimeFrame tf = new StocksTradeTimeFrame(300, 200, 1);
        tf.AddRow(new StockTrade("AAPL", 0.1, 300, 20, 0.8, 1000.0, "2023-01-01"));
        tf.AddRow(new StockTrade("AAPL", 0.1, 200, 20, 0.8, 1000.0, "2023-02-01"));
        
        simulation.AddTimeFrame(tf);
        double score = simulation.calculateSimulationScore();
        
        assertTrue(score > 0.0, "Risk-adjusted score should be positive for consistent gains");
    }
}
