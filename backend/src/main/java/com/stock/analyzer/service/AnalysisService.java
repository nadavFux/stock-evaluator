package com.stock.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.stock.analyzer.core.Pipeline;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StatsCalculator;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * High-level service orchestrating data acquisition, parameter optimization,
 * AI model training, and recommendation generation.
 */
@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final StockDataService dataService;
    private final GraphingService graphingService;
    private boolean isRunning = false;

    public AnalysisService(SimpMessagingTemplate messagingTemplate, StockDataService dataService, GraphingService graphingService) {
        this.messagingTemplate = messagingTemplate;
        this.dataService = dataService;
        this.graphingService = graphingService;
    }

    public void runAnalysis(SimulationRangeConfig config) {
        if (isRunning) return;

        CompletableFuture.runAsync(() -> {
            isRunning = true;
            try {
                StatsCalculator.init(config);
                List<StockGraphState> allStocks;
                
                try (Pipeline pipeline = new Pipeline(dataService, graphingService)) {
                    broadcast("PROGRESS", "Hydrating stocks...");
                    double minCap = (config.minMarketCap != null && !config.minMarketCap.isEmpty()) ? config.minMarketCap.get(0) : 50_000_000.0;
                    allStocks = pipeline.processSectors(config.sectors, config.exchanges, minCap, msg -> broadcast("PROGRESS", msg));
                }

                broadcast("PROGRESS", "Optimizing parameters...");
                ParamOptimizer optimizer = new ParamOptimizer(config);
                SimulationParams bestParams = optimizer.optimize(allStocks);

                // Save best params
                new ObjectMapper(new YAMLFactory()).writeValue(new File(config.outputPath + File.separator + "best_params.yaml"), bestParams);

                broadcast("PROGRESS", "Training AI model...");
                MLModelService mlService = optimizer.getMlService();
                mlService.train();
                mlService.saveModel(config.outputPath + File.separator + "model");
                broadcast("ML_FEATURES", mlService.getFeatureImportance());

                broadcast("PROGRESS", "Generating recommendations...");
                Simulation inferenceSim = new Simulation(bestParams);
                inferenceSim.setMLService(mlService);

                SimulationDataPackage pkg = new SimulationDataPackage(allStocks);
                List<StockCheckResult> recommendations = new ArrayList<>();

                for (int i = 0; i < pkg.stockCount; i++) {
                    SimulationResult res = inferenceSim.evaluateStep(pkg, i, pkg.daysCount - 1);
                    if (res.heuristicScore() > bestParams.buyThreshold()) {
                        List<TradePoint> trades = List.of(new TradePoint(pkg.dates[i][pkg.daysCount-1], pkg.closePrices[i][pkg.daysCount-1], "BUY"));
                        recommendations.add(new StockCheckResult(allStocks.get(i), res, trades));
                    }
                }

                recommendations.sort((a, b) -> Double.compare(b.result().heuristicScore(), a.result().heuristicScore()));
                broadcast("RESULTS", recommendations.stream().limit(100).toList());
                broadcast("STATUS", "Analysis finished successfully.");

            } catch (Exception e) {
                logger.error("Analysis failed", e);
                broadcast("ERROR", e.getMessage());
            } finally {
                isRunning = false;
            }
        });
    }

    public void runBacktest(SimulationRangeConfig config) {
        if (isRunning) return;

        CompletableFuture.runAsync(() -> {
            isRunning = true;
            broadcast("STATUS", "Starting 100-day Backtest...");

            try {
                SimulationParams params = new ObjectMapper(new YAMLFactory()).readValue(new File(config.outputPath + File.separator + "best_params.yaml"), SimulationParams.class);
                
                List<StockGraphState> allStocks;
                try (Pipeline pipeline = new Pipeline(dataService, graphingService)) {
                    allStocks = pipeline.processSectors(config.sectors, config.exchanges, 50_000_000.0, msg -> {});
                }

                Simulation sim = new Simulation(params);
                sim.setMLService(getTrainedMLService(config));
                SimulationDataPackage pkg = new SimulationDataPackage(allStocks);
                
                List<StockTrade> allTrades = new ArrayList<>();
                int backtestDays = 100;

                for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                    for (int i = pkg.daysCount - backtestDays; i < pkg.daysCount; i++) {
                        if (sim.calculateHeuristic(pkg, sIdx, i) > params.buyThreshold()) {
                            double buyPrice = pkg.closePrices[sIdx][i];
                            for (int j = 1; i + j < pkg.daysCount; j++) {
                                int curr = i + j;
                                double ma = pkg.getAvg(sIdx, curr, params.longMovingAvgTime());
                                if (pkg.closePrices[sIdx][curr] < (ma * params.sellCutOffPerc()) || curr == pkg.daysCount - 1) {
                                    double gain = (pkg.closePrices[sIdx][curr] - buyPrice) / buyPrice;
                                    allTrades.add(new StockTrade(pkg.tickers[sIdx], gain, pkg.daysCount - i, j, pkg.closePrices[sIdx][curr], pkg.caps[sIdx][curr], pkg.dates[sIdx][i]));
                                    i = curr;
                                    break;
                                }
                            }
                        }
                    }
                }

                double totalGain = allTrades.stream().mapToDouble(StockTrade::getLastGained).sum() * 100;
                broadcast("BACKTEST_REPORT", Map.of(
                        "totalTrades", allTrades.size(),
                        "totalGain", String.format("%.2f%%", totalGain),
                        "avgGain", String.format("%.2f%%", allTrades.isEmpty() ? 0 : totalGain / allTrades.size()),
                        "trades", allTrades.stream().limit(50).toList()
                ));
                broadcast("STATUS", "Backtest Complete.");

            } catch (Exception e) {
                logger.error("Backtest failed", e);
                broadcast("ERROR", e.getMessage());
            } finally {
                isRunning = false;
            }
        });
    }

    private MLModelService getTrainedMLService(SimulationRangeConfig config) {
        MLModelService service = new MLModelService();
        service.loadModel(config.outputPath + File.separator + "model");
        return service;
    }

    private void broadcast(String type, Object payload) {
        Map<String, Object> message = Map.of("type", type, "payload", payload, "timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/updates", message);
    }

    public boolean isRunning() { return isRunning; }
}
