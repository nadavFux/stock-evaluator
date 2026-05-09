package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TornadoVmOptimizerTest {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizerTest.class);

    @Test
    public void testSingleCandidateParity() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Parity test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = createTestConfig(1, 1, 1);
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        
        int days = 200;
        List<StockGraphState> stockList = createSyntheticStocks(1, days);
        SimulationDataPackage dataPkg = new SimulationDataPackage(stockList);
        SimulationParams params = createDefaultParams();
        
        // 1. Calculate Score on CPU
        Simulation cpuSim = new Simulation(params);
        int timeStart = Math.max(0, days - 200);
        int searchLimit = Math.min(timeStart + 150, days);
        int absoluteLimit = Math.min(timeStart + 150 + 50, days);
        
        // Manual simulation for parity
        for (int i = timeStart; i < searchLimit; i++) {
            if (cpuSim.calculateHeuristic(dataPkg, 0, i) > params.buyThreshold()) {
                double buyPrice = dataPkg.closePrices[0][i];
                for (int j = 1; j < absoluteLimit - i; j++) {
                    int curr = i + j;
                    double price = dataPkg.closePrices[0][curr];
                    double ma = dataPkg.getAvg(0, curr, params.longMovingAvgTime());
                    if (price < (ma * params.sellCutOffPerc()) || (curr == absoluteLimit - 1)) {
                        cpuSim.recordTrade((price - buyPrice) / buyPrice, j);
                        i = curr;
                        break;
                    }
                }
            }
        }
        double cpuScore = cpuSim.calculateScore(1 * 1); // 1 stock, 1 grid point
        
        // 2. Calculate Score on GPU
        List<Optimizer.CandidateResult> gpuResults = gpuOptimizer.evaluateGpu2D(List.of(params), IntArray.fromArray(new int[]{0}), dataPkg, false);
        double gpuScore = gpuResults.get(0).score();
        
        logger.info("Single Candidate Parity - CPU Score: {}, GPU Score: {}", cpuScore, gpuScore);
        
        // Tolerance for float/double drift and simple interest approximation
        assertEquals(cpuScore, gpuScore, 1.0, "CPU and GPU scores should be reasonably close for the same candidate");
    }

    @Test
    public void testFullPipelineE2E() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping E2E test: TornadoVM not available.");
            return;
        }

        // Small workload for fast tests
        SimulationRangeConfig config = createTestConfig(2, 50, 2);
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);

        int stocks = 5;
        int days = 150;
        List<StockGraphState> stockList = createSyntheticStocks(stocks, days);

        logger.info("Starting GPU E2E optimization (Small Workload)...");
        SimulationParams gpuBest = gpuOptimizer.optimize(stockList);
        assertNotNull(gpuBest);
        logger.info("E2E Complete. GPU Best Buy Threshold: {}", gpuBest.buyThreshold());
    }

    private List<StockGraphState> createSyntheticStocks(int count, int days) {
        List<StockGraphState> stockList = new ArrayList<>();
        Random random = new Random(42); // Deterministic
        for (int i = 0; i < count; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int j = 0; j < days; j++) {
                // Generate some volatility and trends to trigger trades
                p *= (1.0 + (random.nextDouble() * 0.04 - 0.015)); 
                prices.add(p);
            }
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.0), 50.0, 150.0, p, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }
        return stockList;
    }

    private SimulationRangeConfig createTestConfig(int centers, int pop, int gens) {
        SimulationRangeConfig config = new SimulationRangeConfig();
        config.centersCount = centers;
        config.populationSize = pop;
        config.generations = gens;
        
        config.startTimes = List.of(200);
        config.searchTimes = List.of(150);
        config.selectTimes = List.of(50);
        config.longMovingAvgTimes = List.of(50);
        config.buyThreshold = List.of(0.65);
        config.riskFreeRate = List.of(0.1);
        config.sellCutOffPerc = List.of(0.93);
        config.lowerPriceToLongAvgBuyIn = List.of(0.95);
        config.higherPriceToLongAvgBuyIn = List.of(1.05);
        config.movingAvgGapWeight = List.of(0.2);
        
        config.timeFrameForUpwardLongAvg = List.of(20);
        config.aboveAvgRatingPricePerc = List.of(1.05);
        config.timeFrameForUpwardShortPrice = List.of(10);
        config.timeFrameForOscillator = List.of(14);
        config.maxRSI = List.of(70.0);
        config.minMarketCap = List.of(1000000.0);
        config.maxMarketCap = List.of(1000000000000.0);
        config.minRatesOfAvgInc = List.of(1.002);
        config.maxPERatios = Arrays.asList(100); 
        config.minRatings = List.of(1.0);
        config.maxRatings = List.of(5.0);
        
        config.reversionToMeanWeight = List.of(0.15);
        config.ratingWeight = List.of(0.2);
        config.upwardIncRateWeight = List.of(0.15);
        config.rvolWeight = List.of(0.1);
        config.pegWeight = List.of(0.1);
        config.volatilityCompressionWeight = List.of(0.1);
        
        config.sectors = List.of(1);
        config.exchanges = List.of("NYSE");
        config.outputPath = "output_test";
        
        return config;
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 1000000, 20, 1.002, 100, 1, 5, 10000000000L, 0.0, 0.65,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }
}
