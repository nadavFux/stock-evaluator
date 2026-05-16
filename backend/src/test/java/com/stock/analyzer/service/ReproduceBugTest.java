package com.stock.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.analyzer.model.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ReproduceBugTest {
    private static final Logger logger = LoggerFactory.getLogger(ReproduceBugTest.class);

    @Test
    public void reproduceConvergenceIssue() throws Exception {
        if (!TornadoVmOptimizer.isAvailable()) {
            logger.warn("Skipping ReproduceBugTest: TornadoVM not available.");
            return;
        }

        // 1. Load config from baserequest.json
        ObjectMapper mapper = new ObjectMapper();
        File jsonFile = new File("baserequest.json");
        SimulationRangeConfig config = mapper.readValue(jsonFile, SimulationRangeConfig.class);
        
        // Force small generations for faster reproduction
        config.generations = 2;
        config.populationSize = 100;
        config.centersCount = 2;

        TornadoVmOptimizer gpuOptimizer = new TornadoVmOptimizer(config);

        // 2. Create synthetic data
        int stocksCount = 2; // Very small for fast JIT
        int days = 100;
        List<StockGraphState> stockList = createSyntheticStocks(stocksCount, days);

        logger.info("Starting reproduction run with baserequest.json config...");
        // Override evaluateGpu2D to log scores if possible, or just observe output
        SimulationParams best = gpuOptimizer.optimize(stockList);
        
        assertNotNull(best);
        logger.info("Reproduction run complete. Best Buy Threshold: {}", best.buyThreshold());
    }

    private List<StockGraphState> createSyntheticStocks(int count, int days) {
        List<StockGraphState> stockList = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < count; i++) {
            Stock stock = new Stock("ID"+i, "NAME"+i, "STK"+i, "EXCH", "2023-01-01", 1000000.0f, 1.0, 1.0, "ID"+i, "NAME"+i, 1.0);
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int j = 0; j < days; j++) {
                p *= (1.0 + (random.nextDouble() * 0.04 - 0.015)); 
                prices.add(p);
            }
            stockList.add(new StockGraphState(stock, Collections.nCopies(days, 4.0), 50.0, 150.0, p, prices, prices, prices, Collections.nCopies(days, "2023-01-01"), Collections.nCopies(days, 1.0), Collections.nCopies(days, 2000000000.0)));
        }
        return stockList;
    }
}
