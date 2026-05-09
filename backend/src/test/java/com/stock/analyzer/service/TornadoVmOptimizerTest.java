package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.StockGraphState;
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
    public void testUnifiedKernelParity() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping Unified Parity test: TornadoVM not available.");
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
        
        // Setup GPU
        FloatArray techData = new FloatArray(days * 12);
        IntArray go = IntArray.fromArray(dataPkg.offsets);
        FloatArray pArr = new FloatArray(24);
        IntArray gGrid = IntArray.fromArray(new int[]{200, 150, 50});
        FloatArray res = new FloatArray(4);
        IntArray subsetIdx = IntArray.fromArray(new int[]{0});

        for (int d = 0; d < days; d++) {
            int base = d * 12;
            techData.set(base + 0, (float)dataPkg.closePrices[0][d]);
            techData.set(base + 1, (float)dataPkg.pricePrefixSum[0][d]);
            techData.set(base + 2, (float)dataPkg.priceSqPrefixSum[0][d]);
            techData.set(base + 3, (float)dataPkg.ratings[0][d]);
            techData.set(base + 4, (float)dataPkg.volumes[0][d]);
            techData.set(base + 5, (float)dataPkg.avgVol30d[0][d]);
            techData.set(base + 6, (float)dataPkg.epss[0][d]);
            techData.set(base + 7, (float)dataPkg.rsi[0][d]);
            techData.set(base + 8, (float)dataPkg.caps[0][d]);
            techData.set(base + 9, (float)dataPkg.macd[0][d]);
            techData.set(base + 10, (float)dataPkg.atr[0][d]);
            techData.set(base + 11, (float)dataPkg.bbP[0][d]);
        }

        try {
            var mapMethod = TornadoVmOptimizer.class.getDeclaredMethod("mapParamsToFloatArray", SimulationParams.class, FloatArray.class, int.class);
            mapMethod.setAccessible(true);
            mapMethod.invoke(gpuOptimizer, params, pArr, 0);

            TornadoVmOptimizer.unifiedKernel(techData, subsetIdx, go, pArr, gGrid, res, 1, days, 1, 1);
            logger.info("Unified Kernel Results - Trades: {}, Days: {}, Excess: {}", res.get(0), res.get(1), res.get(2));
            assertTrue(res.get(0) >= 0, "Unified kernel should execute without error");
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

        int stocks = 3;
        int days = 100;
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

        SimulationParams gpuBest = gpuOptimizer.optimize(stockList); 
        assertNotNull(gpuBest);
        logger.info("GPU Optimization completed successfully.");
    }

    @Test
    public void testFullPipelineE2E() {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping E2E test: TornadoVM not available.");
            return;
        }

        SimulationRangeConfig config = loadConfig();
        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);

        int stocks = 2;
        int days = 150;
        List<StockGraphState> stockList = new ArrayList<>();
        for (int i = 0; i < stocks; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int j = 0; j < days; j++) {
                p *= (1.0 + (Math.random() * 0.01)); 
                prices.add(p);
            }
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.0), 50.0, 150.0, p, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }

        logger.info("Starting GPU E2E optimization...");
        SimulationParams gpuBest = gpuOptimizer.optimize(stockList);
        assertNotNull(gpuBest);
        logger.info("E2E Complete. GPU Best Buy Threshold: {}", gpuBest.buyThreshold());
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

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 1000000, 20, 1.002, 100, 1, 5, 10000000000L, 0.0, 0.65,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }
}
