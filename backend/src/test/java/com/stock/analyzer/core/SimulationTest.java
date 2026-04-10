package com.stock.analyzer.core;

import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class SimulationTest {

    private Stock createDummyStock(String ticker) {
        return new Stock("id", "name", ticker, "exchange", "2023-01-01", 1000f, 1.0, 1.0, "ident", "other", 1.0);
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 1000000, 200, 1.0, 30, 1.0, 5.0, 1000000000, 0.05
        );
    }

    @Test
    public void testHeuristicScoreCalculation() {
        SimulationParams params = createDefaultParams();
        Simulation simulation = new Simulation(params);
        
        // Mock data where everything is "perfect" according to params
        // maGap = 1.0 (between 0.9 and 1.1) -> 0.5 normalized
        // distFromMA = 0.0 -> 1.0 normalized
        // rating = 3.0 (between 1.0 and 5.0) -> 0.5 normalized
        // momentum = 1.15 (between 1.0 and 1.3) -> 0.5 normalized
        // volatility = 0.025 (between 0.0 and 0.05) -> 0.5 normalized
        
        // We need to construct a SimulationDataPackage that gives these values
        Stock s1 = createDummyStock("AAPL");
        int size = 300;
        List<Double> prices = new java.util.ArrayList<>();
        for(int i=0; i<size; i++) prices.add(100.0);
        
        List<Double> ratings = new java.util.ArrayList<>();
        for(int i=0; i<size; i++) ratings.add(3.0);
        
        StockGraphState stock = new StockGraphState(s1, ratings, 0.0, 0.0, 0.0, prices, prices, prices, 
            java.util.Collections.nCopies(size, "date"), prices, prices);

        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stock));
        
        SimulationResult res = simulation.calculateFastScore(pkg, 0, size - 1);
        
        // Weights:
        // movingAvgGapWeight: 0.2
        // reversionToMeanWeight: 0.15
        // ratingWeight: 0.15
        // upwardIncRateWeight: 0.15
        // volatilityCompressionWeight: 0.1
        // (Note: ScoringWeights.defaultWeights() has these values)
        
        // maScore (maGap=1.0, min=0.9, max=1.1) = 1.0 - (1.0 - 0.9) / (1.1 - 0.9) = 1.0 - 0.5 = 0.5
        // reversionScore (dist=0.0, max=0.2) = (0.0 / 0.2) = 0.0
        // ratingScore (rating=3.0, min=1.0, max=5.0) = (3.0 - 1.0) / (5.0 - 1.0) = 2.0 / 4.0 = 0.5
        // momentumScore (momentum=1.0, min=1.0, max=1.3) = (1.0 - 1.0) / 0.3 = 0.0
        // rvolScore (rvol=1.0, min=0.5, max=2.0) = (1.0 - 0.5) / 1.5 = 0.3333
        // pegScore (epsGrowth=0, peg=2.0, max=2.0) = 1.0 - (2.0 / 2.0) = 0.0
        // volScore (vol=0.0, max=0.05) = 1.0 - (0.0 / 0.05) = 1.0
        
        ScoringWeights w = ScoringWeights.defaultWeights();
        double expected = (0.5 * w.movingAvgGapWeight()) +
                          (0.0 * w.reversionToMeanWeight()) +
                          (0.5 * w.ratingWeight()) +
                          (0.0 * w.upwardIncRateWeight()) +
                          (0.3333333333333333 * w.rvolWeight()) +
                          (0.0 * w.pegWeight()) +
                          (1.0 * w.volatilityCompressionWeight());
        
        assertEquals(expected, res.heuristicScore(), 0.001);
    }
}
