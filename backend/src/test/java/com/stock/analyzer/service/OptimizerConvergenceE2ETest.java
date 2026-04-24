package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StatsCalculator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OptimizerConvergenceE2ETest {

    @Test
    public void testOptimizerConvergence() {
        // 1. Setup Synthetic Data
        // We create a "Gold Mine" pattern: 
        // IF RSI < 30 AND Price/MA < 0.9 THEN Gain = 20% after 30 days
        List<StockGraphState> stocks = new ArrayList<>();
        int days = 500;
        
        for (int i = 0; i < 400; i++) {
            String ticker = "TEST" + i;
            List<Double> prices = new ArrayList<>();
            List<Double> volumes = new ArrayList<>();
            List<Double> ratings = new ArrayList<>();
            List<Double> epss = new ArrayList<>();
            List<Double> caps = new ArrayList<>();
            List<String> dates = new ArrayList<>();

            for (int d = 0; d < days; d++) {
                prices.add(100.0);
                volumes.add(1000000.0);
                ratings.add(5.0);
                epss.add(5.0);
                caps.add(1000000000.0);
                dates.add("2023-01-" + (d % 28 + 1));
            }

            double currentPrice = 100.0;
            for (int d = 0; d < days; d++) {
                // Background noise: Very stable
                currentPrice *= (1.0 + (Math.random() * 0.002 - 0.001));
                
                // Embed "Gold Mine" triggers: Massive dip then recovery
                if (d == 100 || d == 200 || d == 300 || d == 400) {
                    currentPrice = 50.0; // 50% drop
                    volumes.set(d, 5000000.0); // 5x volume
                }
                if (d == 101 || d == 201 || d == 301 || d == 401) {
                    currentPrice = 55.0; // 10% recovery from bottom
                    volumes.set(d, 5000000.0);
                }
                
                // Embed Gains (Price goes to 100 after 30 days)
                if (d == 131 || d == 231 || d == 331 || d == 431) {
                    currentPrice = 100.0; 
                }

                prices.set(d, currentPrice);
            }
            stocks.add(new StockGraphState(new Stock(ticker, ticker, ticker, "NYSE", "2023", 0.1f, 0.0, 0.0, "id", "sector", 0.0),
                    ratings, 0.0, 0.0, 0.0, prices, volumes, new ArrayList<>(), dates, epss, caps));
        }

        // 2. Run Optimizer
        SimulationRangeConfig config = new SimulationRangeConfig();
        config.sellCutOffPerc = new ArrayList<>(List.of(0.99));
        config.lowerPriceToLongAvgBuyIn = new ArrayList<>(List.of(0.30)); // Too tight!
        config.higherPriceToLongAvgBuyIn = new ArrayList<>(List.of(0.40)); // Too tight!
        config.timeFrameForUpwardLongAvg = new ArrayList<>(List.of(20));
        config.aboveAvgRatingPricePerc = new ArrayList<>(List.of(1.5));
        config.timeFrameForUpwardShortPrice = new ArrayList<>(List.of(5));
        config.timeFrameForOscillator = new ArrayList<>(List.of(14));
        config.maxRSI = new ArrayList<>(List.of(5.0)); // Too tight!
        config.minMarketCap = new ArrayList<>(List.of(1000.0));
        config.longMovingAvgTimes = new ArrayList<>(List.of(100));
        config.minRatesOfAvgInc = new ArrayList<>(List.of(0.95)); // Too tight!
        config.maxPERatios = new ArrayList<>(List.of(90));
        config.minRatings = new ArrayList<>(List.of(4.5));
        config.maxRatings = new ArrayList<>(List.of(5.0));
        config.maxMarketCap = new ArrayList<>(List.of(100000000000.0));
        config.riskFreeRate = new ArrayList<>(List.of(0.05));
        config.outputPath = "output_test";
        config.startTimes = List.of(400);
        config.searchTimes = List.of(300);
        config.selectTimes = List.of(1);

        ParamOptimizer optimizer = new ParamOptimizer(config);
        SimulationParams optimizedParams = optimizer.optimize(stocks);
        
        // 3. Assertions
        assertTrue(optimizedParams.lowerPriceToLongAvgBuyIn() > 0.30 || optimizedParams.maxRSI() > 5.0, 
                "Optimizer should have loosened parameters to capture the synthetic pattern");
        
        // Check ML Model Data Collection & Training
        MLModelService mlService = optimizer.getMlService();
        mlService.train(); // Train on the collected 400+ samples
        
        // Inference Check: Create a sequence that matches the trigger
        float[][] triggerSequence = new float[30][12];
        for(int i=0; i<30; i++) Arrays.fill(triggerSequence[i], 0.5f);
        // Set the current step (index 29) to match our "low price, low RSI" trigger
        triggerSequence[29][0] = 0.8f; // MA Gap
        triggerSequence[29][7] = 25.0f; // RSI
        
        double[] prediction = mlService.predict(triggerSequence);
        // We expect Q50 to be non-zero and at least not a large loss
        assertTrue(prediction[1] > -0.1, "ML model should not predict a significant loss for the trigger pattern (Q50: " + prediction[1] + ")");
    }
}
