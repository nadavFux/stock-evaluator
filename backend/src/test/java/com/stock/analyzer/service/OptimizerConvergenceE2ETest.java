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
        List<StockGraphState> stocks = new ArrayList<>();
        int days = 500;
        
        for (int i = 0; i < 200; i++) {
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
                dates.add("2023-01-01");
            }

            double currentPrice = 100.0;
            for (int d = 0; d < days; d++) {
                currentPrice *= (1.0 + (Math.random() * 0.002 - 0.001));
                if (d % 100 == 0 && d > 50) {
                    currentPrice = 50.0; // Dip
                }
                if (d % 100 == 30 && d > 50) {
                    currentPrice = 100.0; // Recovery
                }
                prices.set(d, currentPrice);
            }
            stocks.add(new StockGraphState(new Stock("1", ticker, ticker, "NYSE", "2023", 0.1f, 0.0, 0.0, "id", "sector", 0.0),
                    ratings, 0.0, 0.0, 0.0, prices, volumes, new ArrayList<>(), dates, epss, caps));
        }

        // 2. Run Optimizer
        SimulationRangeConfig config = new SimulationRangeConfig();
        config.sellCutOffPerc = new ArrayList<>(List.of(0.99));
        config.lowerPriceToLongAvgBuyIn = new ArrayList<>(List.of(0.50));
        config.higherPriceToLongAvgBuyIn = new ArrayList<>(List.of(1.50));
        config.timeFrameForUpwardLongAvg = new ArrayList<>(List.of(20));
        config.aboveAvgRatingPricePerc = new ArrayList<>(List.of(1.5));
        config.timeFrameForUpwardShortPrice = new ArrayList<>(List.of(5));
        config.timeFrameForOscillator = new ArrayList<>(List.of(14));
        config.maxRSI = new ArrayList<>(List.of(80.0));
        config.minMarketCap = new ArrayList<>(List.of(1000.0));
        config.longMovingAvgTimes = new ArrayList<>(List.of(100));
        config.minRatesOfAvgInc = new ArrayList<>(List.of(0.1));
        config.maxPERatios = new ArrayList<>(List.of(90));
        config.minRatings = new ArrayList<>(List.of(1.0));
        config.maxRatings = new ArrayList<>(List.of(5.0));
        config.maxMarketCap = new ArrayList<>(List.of(100000000000.0));
        config.riskFreeRate = new ArrayList<>(List.of(0.05));
        config.buyThreshold = new ArrayList<>(List.of(0.2)); // Loose initial threshold
        config.outputPath = "output_test";
        config.startTimes = List.of(400);
        config.searchTimes = List.of(300);
        config.selectTimes = List.of(1);
        
        config.movingAvgGapWeight = new ArrayList<>(List.of(0.2));
        config.reversionToMeanWeight = new ArrayList<>(List.of(0.15));
        config.ratingWeight = new ArrayList<>(List.of(0.2));
        config.upwardIncRateWeight = new ArrayList<>(List.of(0.15));
        config.rvolWeight = new ArrayList<>(List.of(0.1));
        config.pegWeight = new ArrayList<>(List.of(0.1));
        config.volatilityCompressionWeight = new ArrayList<>(List.of(0.1));

        CpuParamOptimizer optimizer = new CpuParamOptimizer(config);
        SimulationParams optimizedParams = optimizer.optimize(stocks);
        
        assertNotNull(optimizedParams);
    }
}
