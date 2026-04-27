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
    public void testDataHydration() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Hydration test: TornadoVM not available.");
            return;
        }
        
        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        
        try {
            var method = TornadoVmOptimizer.class.getDeclaredMethod("evaluateGpu2D", List.class, IntArray.class, SimulationDataPackage.class, boolean.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, IntArray.class);
            assertNotNull(method);
        } catch (NoSuchMethodException e) {
            fail("Should have found evaluateGpu2D with 14 parameters: " + e.getMessage());
        }
    }

    @Test
    public void testHeuristicKernelParity() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Heuristic Parity test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        
        int stocks = 1;
        int days = 100;
        List<StockGraphState> stockList = new ArrayList<>();
        Stock stock = new Stock("ID", "NAME", "STK", "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID", "NAME", 1.0);
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < days; i++) prices.add(100.0 + i);
        stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.0), 50.0, 150.0, 100.0, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        
        SimulationDataPackage dataPkg = new SimulationDataPackage(stockList);
        SimulationParams params = createDefaultParams();
        Simulation sim = new Simulation(params);
        
        double cpuH = sim.calculateHeuristic(dataPkg, 0, 80);
        
        // Setup GPU
        IntArray gpuSubset = IntArray.fromArray(new int[]{0});
        DoubleArray gp = DoubleArray.fromArray(flatten(dataPkg.closePrices, 1, days));
        DoubleArray gps = DoubleArray.fromArray(flatten(dataPkg.pricePrefixSum, 1, days));
        DoubleArray gpsq = DoubleArray.fromArray(flatten(dataPkg.priceSqPrefixSum, 1, days));
        DoubleArray gr = DoubleArray.fromArray(flatten(dataPkg.ratings, 1, days));
        DoubleArray gv = DoubleArray.fromArray(flatten(dataPkg.volumes, 1, days));
        DoubleArray gav = DoubleArray.fromArray(flatten(dataPkg.avgVol30d, 1, days));
        DoubleArray ge = DoubleArray.fromArray(flatten(dataPkg.epss, 1, days));
        DoubleArray grsi = DoubleArray.fromArray(flatten(dataPkg.rsi, 1, days));
        DoubleArray gc = DoubleArray.fromArray(flatten(dataPkg.caps, 1, days));
        IntArray go = IntArray.fromArray(dataPkg.offsets);
        
        DoubleArray hScores = new DoubleArray(days);
        DoubleArray pArr = new DoubleArray(24);
        DoubleArray iMat = new DoubleArray(days * 5); // 5 indicators

        try {
            var mapMethod = TornadoVmOptimizer.class.getDeclaredMethod("mapParamsToArray", SimulationParams.class, DoubleArray.class, int.class);
            mapMethod.setAccessible(true);
            mapMethod.invoke(gpuOptimizer, params, pArr, 0);

            DoubleArray sMat = new DoubleArray(days * 9);
            for(int d=0; d<days; d++) {
                sMat.set(0*days + d, gp.get(d));
                sMat.set(1*days + d, gps.get(d));
                sMat.set(2*days + d, gpsq.get(d));
                sMat.set(3*days + d, gr.get(d));
                sMat.set(4*days + d, gv.get(d));
                sMat.set(5*days + d, gav.get(d));
                sMat.set(6*days + d, ge.get(d));
                sMat.set(7*days + d, grsi.get(d));
                sMat.set(8*days + d, gc.get(d));
            }

            TornadoVmOptimizer.indicatorKernel(sMat, go, iMat, 1, days);
            TornadoVmOptimizer.heuristicKernel(sMat, iMat, go, pArr, hScores, 1, days, 1);
            double gpuH = hScores.get(80);
            logger.info("Heuristic Parity - CPU: {}, GPU: {}", cpuH, gpuH);
            assertEquals(cpuH, gpuH, 0.001);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
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
        DoubleArray ge = DoubleArray.fromArray(flatten(dataPkg.epss, stocks, days));
        DoubleArray grsi = DoubleArray.fromArray(flatten(dataPkg.rsi, stocks, days));
        DoubleArray gc = DoubleArray.fromArray(flatten(dataPkg.caps, stocks, days));
        IntArray go = IntArray.fromArray(dataPkg.offsets);

        try {
            var method = TornadoVmOptimizer.class.getDeclaredMethod("evaluateGpu2D", List.class, IntArray.class, SimulationDataPackage.class, boolean.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, 
                                                                    DoubleArray.class, DoubleArray.class, DoubleArray.class, DoubleArray.class, IntArray.class);
            method.setAccessible(true);
            List<CandidateResult> gpuResults = (List<CandidateResult>) method.invoke(gpuOptimizer, List.of(params), gpuSubset, dataPkg, true, 
                                                                    gp, gps, gpsq, gr, gv, gav, ge, grsi, gc, go);
            double gpuScore = gpuResults.get(0).score();

            logger.info("Parity Check (Rescue Mode) - CPU Score: {}, GPU Score: {}", cpuScore, gpuScore);
            assertEquals(cpuScore, gpuScore, 0.001, "GPU and CPU should find the exact same number of trades");
        } catch (Exception e) {
            e.printStackTrace();
            fail("Parity check failed: " + e.getMessage());
        }
    }

    @Test
    public void testSimulationKernelParity() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Simulation Parity test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        
        int stocks = 1;
        int days = 200;
        List<StockGraphState> stockList = new ArrayList<>();
        Stock stock = new Stock("ID", "NAME", "STK", "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID", "NAME", 1.0);
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < days; i++) prices.add(100.0 + i);
        stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.0), 50.0, 150.0, 100.0, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        
        SimulationDataPackage dataPkg = new SimulationDataPackage(stockList);
        SimulationParams params = createDefaultParams();
        
        IntArray go = IntArray.fromArray(dataPkg.offsets);
        DoubleArray pArr = new DoubleArray(24);
        IntArray gGrid = IntArray.fromArray(new int[]{200, 150, 50});
        DoubleArray res = new DoubleArray(4);
        DoubleArray sMat = new DoubleArray(days * 9);
        DoubleArray hScores = new DoubleArray(days);
        for(int i=0; i<days; i++) {
            sMat.set(0*days + i, prices.get(i));
            sMat.set(1*days + i, dataPkg.pricePrefixSum[0][i]);
            hScores.set(i, 0.8); // Always buy
        }

        try {
            var mapMethod = TornadoVmOptimizer.class.getDeclaredMethod("mapParamsToArray", SimulationParams.class, DoubleArray.class, int.class);
            mapMethod.setAccessible(true);
            mapMethod.invoke(gpuOptimizer, params, pArr, 0);

            TornadoVmOptimizer.simulationKernel(sMat, go, hScores, pArr, gGrid, res, 1, days, 1, 1);
            logger.info("Simulation Kernel Results - Trades: {}, Days: {}, Excess: {}", res.get(0), res.get(1), res.get(2));
            assertTrue(res.get(0) > 0, "Should have recorded at least one trade");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testFullPipelineE2E() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping E2E test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        CpuParamOptimizer cpuOptimizer = new CpuParamOptimizer(config);

        int stocks = 5;
        int days = 200;
        List<StockGraphState> stockList = new ArrayList<>();
        for (int i = 0; i < stocks; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int j = 0; j < days; j++) {
                p *= 1.005; 
                prices.add(p);
            }
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.5), 50.0, 150.0, p, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }

        logger.info("Starting GPU E2E optimization...");
        SimulationParams gpuBest = gpuOptimizer.optimize(stockList);
        assertNotNull(gpuBest);
        
        logger.info("Starting CPU E2E optimization...");
        SimulationParams cpuBest = cpuOptimizer.optimize(stockList);
        assertNotNull(cpuBest);
        
        // They won't be identical due to randomness, but they should both produce reasonable params
        logger.info("E2E Complete. GPU Best: {}, CPU Best: {}", gpuBest.buyThreshold(), cpuBest.buyThreshold());
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
