package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.service.Optimizer.CandidateResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

public class TornadoVmOptimizerTest {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizerTest.class);

    @Test
    public void testSafetyAvailabilityCheck() {
        boolean available = TornadoVmOptimizer.isAvailable();
        logger.info("TornadoVM availability in test environment: {}", available);
    }

    @Test
    public void testFactoryFallbackLogic() {
        SimulationRangeConfig config = loadConfig();
        Optimizer optimizer = OptimizerFactory.create("gpu", config);
        
        if (TornadoVmOptimizer.isAvailable()) {
            assertTrue(optimizer instanceof TornadoVmOptimizer, "Should return GPU optimizer when available");
        } else {
            assertTrue(optimizer instanceof CpuParamOptimizer, "Should fallback to CPU optimizer when GPU is unavailable");
        }
    }

    @Test
    public void testCandidateEvaluationLogic() {
        SimulationParams params = createDefaultParams();
        Simulation sim = new Simulation(params);
        sim.recordTrade(0.05, 10);
        sim.recordTrade(0.10, 20);
        double score = sim.calculateScore(1);
        assertTrue(score > -100, "Score should be calculated correctly");
    }

    @Test
    public void testRandomizeRespectsLogicalBounds() {
        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(loadConfig());
        SimulationParams center = createDefaultParams();
        for (int i = 0; i < 50; i++) {
            SimulationParams p = optimizer.randomize(center, 0.2);
            assertWithin(p.sellCutOffPerc(), 0.1, 0.99, "sellCutOffPerc");
            assertWithin(p.buyThreshold(), 0.4, 0.95, "buyThreshold");
            assertTrue(p.longMovingAvgTime() >= 10, "longMovingAvgTime too small");
        }
    }

    @Test
    public void testGpuCpuParity() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Parity test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        CpuParamOptimizer cpuOptimizer = new CpuParamOptimizer(config);

        int stocks = 5;
        int days = 200;
        List<StockGraphState> stockList = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < stocks; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int j = 0; j < days; j++) {
                p *= 1.005; // Upward bias
                prices.add(p);
            }
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.5), 50.0, 150.0, p, prices, Collections.nCopies(days, 1000.0), prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }

        SimulationDataPackage dataPkg = new SimulationDataPackage(stockList);
        SimulationParams params = createDefaultParams();

        List<Optimizer.CandidateResult> cpuResults = cpuOptimizer.evaluateParallel(List.of(params), java.util.stream.IntStream.range(0, stocks).boxed().toList(), dataPkg, true);
        double cpuScore = cpuResults.get(0).score();

        IntArray gpuSubset = IntArray.fromArray(java.util.stream.IntStream.range(0, stocks).toArray());
        DoubleArray gp = DoubleArray.fromArray(flatten(dataPkg.closePrices, stocks, days));
        DoubleArray gps = DoubleArray.fromArray(flatten(dataPkg.pricePrefixSum, stocks, days));
        DoubleArray gpsq = DoubleArray.fromArray(flatten(dataPkg.priceSqPrefixSum, stocks, days));
        DoubleArray gr = DoubleArray.fromArray(flatten(dataPkg.ratings, stocks, days));
        DoubleArray gv = DoubleArray.fromArray(flatten(dataPkg.volumes, stocks, days));
        DoubleArray gav = DoubleArray.fromArray(flatten(dataPkg.avgVol30d, stocks, days));
        IntArray go = IntArray.fromArray(dataPkg.offsets);

        try {
            var method = TornadoVmOptimizer.class.getDeclaredMethod("evaluateGpu2D", List.class, IntArray.class, SimulationDataPackage.class, boolean.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, IntArray.class);
            method.setAccessible(true);
            List<CandidateResult> gpuResults = (List<CandidateResult>) method.invoke(gpuOptimizer, List.of(params), gpuSubset, dataPkg, true, gp, gps, gpsq, gr, gv, gav, go);
            double gpuScore = gpuResults.get(0).score();

            logger.info("Parity Check (Rescue Mode) - CPU Score: {}, GPU Score: {}", cpuScore, gpuScore);
            assertEquals(cpuScore, gpuScore, 0.001, "GPU and CPU should find the exact same number of trades");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Parity check failed: " + e.getMessage());
        }
    }

    @Test
    public void testFullPipelineExecution() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping GPU pipeline test: TornadoVM not available.");
            return;
        }

        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(loadConfig());
        int stocks = 2;
        int days = 100;
        List<StockGraphState> stockList = new ArrayList<>();
        for (int i = 0; i < stocks; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = Collections.nCopies(days, 100.0 + i);
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 3.0), 90.0, 110.0, 100.0, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }
        
        SimulationDataPackage dataPkg = new SimulationDataPackage(stockList);
        List<SimulationParams> candidates = List.of(createDefaultParams());
        IntArray subset = IntArray.fromArray(new int[]{0, 1});
        DoubleArray gp = DoubleArray.fromArray(flatten(dataPkg.closePrices, stocks, days));
        DoubleArray gps = DoubleArray.fromArray(flatten(dataPkg.pricePrefixSum, stocks, days));
        DoubleArray gpsq = DoubleArray.fromArray(flatten(dataPkg.priceSqPrefixSum, stocks, days));
        DoubleArray gr = DoubleArray.fromArray(flatten(dataPkg.ratings, stocks, days));
        DoubleArray gv = DoubleArray.fromArray(flatten(dataPkg.volumes, stocks, days));
        DoubleArray gav = DoubleArray.fromArray(flatten(dataPkg.avgVol30d, stocks, days));
        IntArray go = IntArray.fromArray(dataPkg.offsets);

        try {
            var method = TornadoVmOptimizer.class.getDeclaredMethod("evaluateGpu2D", List.class, IntArray.class, SimulationDataPackage.class, boolean.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, IntArray.class);
            method.setAccessible(true);
            List<CandidateResult> results = (List<CandidateResult>) method.invoke(optimizer, candidates, subset, dataPkg, false, gp, gps, gpsq, gr, gv, gav, go);
            assertNotNull(results);
            assertFalse(results.isEmpty());
            logger.info("GPU Pipeline execution successful. Score: {}", results.get(0).score());
        } catch (Exception e) {
            e.printStackTrace();
            fail("GPU Pipeline execution failed: " + e.getMessage());
        }
    }

    private SimulationRangeConfig loadConfig() {
        SimulationRangeConfig config = new SimulationRangeConfig();
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
        return config;
    }

    private double[] flatten(double[][] data, int stocks, int days) {
        double[] flat = new double[stocks * days];
        for (int i = 0; i < stocks; i++) System.arraycopy(data[i], 0, flat, i * days, days);
        return flat;
    }

    private void assertWithin(double val, double min, double max, String field) {
        assertTrue(val >= min && val <= max, String.format("Field %s: Value %.2f not within [%.2f, %.2f]", field, val, min, max));
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 1000000, 20, 1.002, 100, 1, 5, 10000000000L, 0.0, 0.65,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }
}
