package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class RecommendationValidationTest {

    @Test
    public void testStrategyLogic() {
        // Use loose parameters and high weights for MA Gap to guarantee a trigger
        SimulationParams params = new SimulationParams(
            0.95, 0.1, 5.0, 50, 5.0, 20, 10, 99, 0, 20, 0.0, 100, 1, 5, 100000000000.0, 0.05, 0.1,
            0.9, 0.01, 0.01, 0.01, 0.01, 0.01, 0.05
        );

        Stock stock = new Stock("1", "AAPL", "AAPL", "NAS", "2023-01-01", 1000000.0f, 4.5, 1.0, "ID", "Other", 1.0);
        
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 300; i++) prices.add(100.0); // Constant price
        
        StockGraphState state = new StockGraphState(
            stock, Collections.nCopies(prices.size(), 4.5), 80.0, 200.0, 100.0,
            prices, Collections.nCopies(prices.size(), 1000000.0),
            Collections.nCopies(prices.size(), 100.0),
            Collections.nCopies(prices.size(), "2023-01-01"),
            Collections.nCopies(prices.size(), 5.0),
            Collections.nCopies(prices.size(), 2000000000.0)
        );

        SimulationDataPackage pkg = new SimulationDataPackage(List.of(state));
        Simulation sim = new Simulation(params);
        
        // At index 290, with constant price and MA, MA Gap should be 1.0.
        // normalize(1.0, 0.1, 5.0) should be > 0.
        double h = sim.calculateHeuristic(pkg, 0, 290);
        assertTrue(h > 0, "Heuristic should be positive for stable data with loose threshold (got: " + h + ")");
    }
}
