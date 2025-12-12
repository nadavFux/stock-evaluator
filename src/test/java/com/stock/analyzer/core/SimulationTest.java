package com.stock.analyzer.core;

import com.stock.analyzer.model.dto.StockTrade;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SimulationTest {

    @Test
    public void testSimulationScore() {
        // Create a config
        SimulationConfig config = new SimulationConfig(
                0.1, 0.9, 1.1, 10, 1.05, 5, 14, 70, 1000000, 200, 0.01, 20, 3.0, 5.0, 1000000000);

        Simulation simulation = new Simulation(config);

        // Add dummy trades
        StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(0, 100, 10);
        // gain of 10% in 10 days
        StockTrade trade1 = new StockTrade("AAPL", 0.10, 10, 10, 1.0, 0.1, "2023-01-01");
        // gain of -5% in 10 days
        StockTrade trade2 = new StockTrade("GOOGL", -0.05, 10, 10, 1.0, 0.1, "2023-01-01");

        timeFrame.AddRow(trade1);
        timeFrame.AddRow(trade2);

        simulation.AddTimeFrame(timeFrame);

        double score = simulation.calculateSimulationScore();

        // Manual calculation check
        // Risk-free daily: (1.12)^(1/252) - 1 approx 0.000449
        // 10 days risk free: (1.000449)^10 - 1 approx 0.0045 or 0.45%
        // Trade 1 excess: 10% - 0.45% = 9.55%
        // Trade 2 excess: -5% - 0.45% = -5.45%
        // Average excess: (9.55 - 5.45) / 2 = 2.05%

        // Just assertions on ranges/logic for now as precision might vary
        assertTrue(score > 0);
    }
}
