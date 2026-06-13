package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class TornadoVmOptimizerTest {

    private SimulationRangeConfig config;

    @BeforeEach
    public void setup() {
        config = new SimulationRangeConfig();
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
        config.buyThreshold = new ArrayList<>(List.of(0.2));
        config.startTimes = List.of(20);
        config.searchTimes = List.of(20);
        config.selectTimes = List.of(1);
        
        // Ensure weights are initialized to avoid NaNs in score calculation
        config.movingAvgGapWeight = new ArrayList<>(List.of(0.2));
        config.reversionToMeanWeight = new ArrayList<>(List.of(0.15));
        config.ratingWeight = new ArrayList<>(List.of(0.2));
        config.upwardIncRateWeight = new ArrayList<>(List.of(0.15));
        config.rvolWeight = new ArrayList<>(List.of(0.1));
        config.pegWeight = new ArrayList<>(List.of(0.1));
        config.volatilityCompressionWeight = new ArrayList<>(List.of(0.1));
    }

    @Test
    public void testIsAvailable_Sanity() {
        assertDoesNotThrow(() -> {
            boolean available = TornadoVmOptimizer.isAvailable();
            System.out.println("TornadoVM available: " + available);
        });
    }

    @Test
    public void testMapParamsToFloatArray_Unit() throws Exception {
        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(config);
        SimulationParams params = new SimulationParams(
                0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0, 0.65,
                0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );

        FloatArray array = new FloatArray(24);

        Method method = TornadoVmOptimizer.class.getDeclaredMethod("mapParamsToFloatArray", SimulationParams.class, FloatArray.class, int.class);
        method.setAccessible(true);
        method.invoke(optimizer, params, array, 0);

        assertEquals(0.95f, array.get(0), 0.001);
        assertEquals(0.9f, array.get(1), 0.001);
        assertEquals(0.1f, array.get(23), 0.001); // volatilityCompressionWeight
    }

    @Test
    public void testPreallocateAndFlatten_Unit() throws Exception {
        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(config);
        List<StockGraphState> stocks = generateSyntheticStocks(2, 50);
        SimulationDataPackage pkg = new SimulationDataPackage(stocks);

        Method allocMethod = TornadoVmOptimizer.class.getDeclaredMethod("preallocateBuffers", int.class, int.class);
        allocMethod.setAccessible(true);
        allocMethod.invoke(optimizer, pkg.stockCount, pkg.daysCount);

        Method flatMethod = TornadoVmOptimizer.class.getDeclaredMethod("flattenToTechData", SimulationDataPackage.class);
        flatMethod.setAccessible(true);
        flatMethod.invoke(optimizer, pkg);

        Field techDataField = TornadoVmOptimizer.class.getDeclaredField("technicalData");
        techDataField.setAccessible(true);
        FloatArray techData = (FloatArray) techDataField.get(optimizer);

        assertNotNull(techData);
        assertTrue(techData.getSize() >= 2 * 50 * 12);
        assertTrue(techData.get(0) > 0); // Price should be mapped
    }

    @Test
    public void testEvaluateGpu2D_Component() throws Exception {
        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(config);
        List<StockGraphState> stocks = generateSyntheticStocks(2, 150); // Increased size to avoid JIT stall
        SimulationDataPackage pkg = new SimulationDataPackage(stocks);

        // Manually trigger allocation for evaluation
        Method allocMethod = TornadoVmOptimizer.class.getDeclaredMethod("preallocateBuffers", int.class, int.class);
        allocMethod.setAccessible(true);
        allocMethod.invoke(optimizer, pkg.stockCount, pkg.daysCount);

        Method flatMethod = TornadoVmOptimizer.class.getDeclaredMethod("flattenToTechData", SimulationDataPackage.class);
        flatMethod.setAccessible(true);
        flatMethod.invoke(optimizer, pkg);

        SimulationParams params = new SimulationParams(
                0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0, 0.05,
                0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );

        IntArray subset = new IntArray(pkg.stockCount);
        for (int i = 0; i < pkg.stockCount; i++) subset.set(i, i);

        List<Optimizer.CandidateResult> results = optimizer.evaluateGpu2D(List.of(params), subset, pkg, false);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertNotNull(results.get(0).params());
    }

    @Test
    public void testFullOptimizationPipeline_E2E() {
        // Use a tiny config to make it run extremely fast
        config.centersCount = 1;
        config.populationSize = 2;
        config.generations = 1;

        TornadoVmOptimizer optimizer = new TornadoVmOptimizer(config);
        List<StockGraphState> stocks = generateSyntheticStocks(2, 150);

        // E2E triggers entire pipeline
        SimulationParams resultParams = optimizer.optimize(stocks);
        assertNotNull(resultParams);
        assertTrue(resultParams.sellCutOffPerc() > 0);
    }

    @Test
    public void testGpuCpuParity() throws Exception {
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);
        CpuParamOptimizer cpuOptimizer = new CpuParamOptimizer(config);

        List<StockGraphState> stocks = generateSyntheticStocks(2, 150);
        SimulationDataPackage pkg = new SimulationDataPackage(stocks);

        // Preallocate buffers and flatten
        Method allocMethod = TornadoVmOptimizer.class.getDeclaredMethod("preallocateBuffers", int.class, int.class);
        allocMethod.setAccessible(true);
        allocMethod.invoke(gpuOptimizer, pkg.stockCount, pkg.daysCount);

        Method flatMethod = TornadoVmOptimizer.class.getDeclaredMethod("flattenToTechData", SimulationDataPackage.class);
        flatMethod.setAccessible(true);
        flatMethod.invoke(gpuOptimizer, pkg);

        // Select a variety of parameters to test different code paths in the kernel
        SimulationParams params = new SimulationParams(
                0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1.0, 5.0, 1000000000.0, 0.05, 0.2,
                0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );

        IntArray subsetGpu = new IntArray(pkg.stockCount);
        List<Integer> subsetCpu = new ArrayList<>();
        for (int i = 0; i < pkg.stockCount; i++) {
            subsetGpu.set(i, i);
            subsetCpu.add(i);
        }

        // Test 1: Standard Evaluation Parity
        List<Optimizer.CandidateResult> gpuResults = gpuOptimizer.evaluateGpu2D(List.of(params), subsetGpu, pkg, false);
        List<Optimizer.CandidateResult> cpuResults = cpuOptimizer.evaluateParallel(List.of(params), subsetCpu, pkg, false);
        List<Optimizer.CandidateResult> gpuRescueResults = gpuOptimizer.evaluateGpu2D(List.of(params), subsetGpu, pkg, true);
        List<Optimizer.CandidateResult> cpuRescueResults = cpuOptimizer.evaluateParallel(List.of(params), subsetCpu, pkg, true);

        assertNotNull(gpuResults);
        assertNotNull(cpuResults);
        assertEquals(1, gpuResults.size());
        assertEquals(1, cpuResults.size());

        double gpuScore = gpuResults.get(0).score();
        double cpuScore = cpuResults.get(0).score();
        double gpuRescueScore = gpuRescueResults.get(0).score();
        double cpuRescueScore = cpuRescueResults.get(0).score();

        System.out.println("=== DIAGNOSTIC PARITY REPORT ===");
        System.out.println("GPU Score (Standard): " + gpuScore);
        System.out.println("CPU Score (Standard): " + cpuScore);
        System.out.println("GPU Trades (Rescue Score + 100): " + (gpuRescueScore + 100.0));
        System.out.println("CPU Trades (Rescue Score + 100): " + (cpuRescueScore + 100.0));
        System.out.println("=================================");

        assertEquals(cpuRescueScore, gpuRescueScore, 0.001, "GPU and CPU trade counts should be exactly equal");
        assertEquals(cpuScore, gpuScore, 0.001, "GPU and CPU standard scores should be within 0.001 of each other");
    }


    public static List<StockGraphState> generateSyntheticStocks(int count, int days) {
        java.util.Random rand = new java.util.Random(42); // Fixed seed
        List<StockGraphState> stocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ticker = "TEST" + i;
            List<Double> prices = new ArrayList<>();
            List<Double> volumes = new ArrayList<>();
            List<Double> ratings = new ArrayList<>();
            List<Double> epss = new ArrayList<>();
            List<Double> caps = new ArrayList<>();
            List<String> dates = new ArrayList<>();

            double currentPrice = 100.0;
            for (int d = 0; d < days; d++) {
                currentPrice *= (1.0 + (rand.nextDouble() * 0.002 - 0.001));
                if (d > days / 2 && d < days / 2 + 5) currentPrice = 50.0; // Artificial dip
                prices.add(currentPrice);
                volumes.add(1000000.0);
                ratings.add(4.5);
                epss.add(5.0);
                caps.add(1000000000.0);
                dates.add("2023-01-01");
            }
            stocks.add(new StockGraphState(new Stock("1", ticker, ticker, "NYSE", "2023", 0.1f, 0.0, 0.0, "id", "sector", 0.0),
                    ratings, 0.0, 0.0, 0.0, prices, volumes, new ArrayList<>(), dates, epss, caps));
        }
        return stocks;
    }
}
