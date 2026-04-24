package com.stock.analyzer.service;

import com.stock.analyzer.core.Pipeline;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StatsCalculator;
import com.stock.analyzer.infra.HttpClientService;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import com.stock.analyzer.infra.StockCacheRepository;
import com.stock.analyzer.model.StockCacheEntity;
import com.stock.analyzer.model.StockTrade;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;

@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final StockDataService dataService;
    private final GraphingService graphingService;
    private final StockCacheRepository cacheRepository;
    private boolean isRunning = false;

    public AnalysisService(SimpMessagingTemplate messagingTemplate, StockDataService dataService, GraphingService graphingService, StockCacheRepository cacheRepository) {
        this.messagingTemplate = messagingTemplate;
        this.dataService = dataService;
        this.graphingService = graphingService;
        this.cacheRepository = cacheRepository;
    }

    public void runAnalysis(SimulationRangeConfig config) {
        if (isRunning) {
            logger.warn("Analysis is already running.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            isRunning = true;
            broadcast("STATUS", "Starting analysis...");
            
            try {
                StatsCalculator.init(config);
                
                Pipeline pipeline = new Pipeline(dataService, graphingService);

                broadcast("PROGRESS", "Hydrating stocks...");
                double minCap = (config.minMarketCap != null && !config.minMarketCap.isEmpty()) ? config.minMarketCap.get(0) : 50_000_000.0;
                List<StockGraphState> allStocks = pipeline.processSectors(config.sectors, config.exchanges, minCap,
                        msg -> broadcast("PROGRESS", msg));

                broadcast("STATUS", "Collected data for " + allStocks.size() + " stocks.");

                broadcast("PROGRESS", "Optimizing parameters...");
                ParamOptimizer optimizer = new ParamOptimizer(config);
                SimulationParams bestParams = optimizer.optimize(allStocks);

                // Save best params to file
                try {
                    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                    mapper.writeValue(new File(config.outputPath + File.separator + "best_params.yaml"), bestParams);
                    logger.info("Saved best parameters to {}", config.outputPath + File.separator + "best_params.yaml");
                } catch (Exception e) {
                    logger.error("Failed to save best_params.yaml", e);
                }

                broadcast("PROGRESS", "Training AI model...");
                MLModelService mlService = optimizer.getMlService();
                mlService.train();
                mlService.saveSamples(config.outputPath + File.separator + "training_samples.csv");
                broadcast("ML_FEATURES", mlService.getFeatureImportance());

                broadcast("PROGRESS", "Generating recommendations...");
                Simulation inferenceSim = new Simulation(bestParams);
                inferenceSim.setMLService(mlService);
                StatsCalculator.AddSimulation(inferenceSim);
                
                List<StockCheckResult> recommendations = allStocks.stream()
                        .map(stock -> {
                            List<Double> movingAvg = StatsCalculator.MovingAvg(stock, bestParams.longMovingAvgTime());
                            SimulationResult res = inferenceSim.calculateDualScore(stock.closePrices(), movingAvg, stock.closePrices().size() - 1, stock.stock(), stock.epss(), stock.rating(), stock.caps(), stock.volumes());
                            
                            List<TradePoint> tradePoints = new ArrayList<>();
                            if (res.heuristicScore() > 0.65) {
                                tradePoints.add(new TradePoint(stock.dates().get(stock.dates().size()-1), stock.closePrices().get(stock.closePrices().size()-1), "BUY"));
                            }

                            if (res.heuristicScore() > 0.6 || res.aiPredictedReturn() > 0.02) {
                                return new StockCheckResult(stock, res, tradePoints);
                            }
                            return null;
                        })
                        .filter(Objects::nonNull)
                        .sorted((a, b) -> Double.compare(b.result().heuristicScore(), a.result().heuristicScore()))
                        .limit(100)
                        .toList();

                broadcast("RESULTS", recommendations);
                StatsCalculator.WriteStat();
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
                // Load best params from file
                File paramsFile = new File(config.outputPath + File.separator + "best_params.yaml");
                if (!paramsFile.exists()) {
                    throw new RuntimeException("best_params.yaml not found in " + config.outputPath + ". Run analysis first.");
                }
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                SimulationParams params = mapper.readValue(paramsFile, SimulationParams.class);
                logger.info("Loaded parameters from {} for backtest", paramsFile.getAbsolutePath());

                StatsCalculator.init(config);
                Pipeline pipeline = new Pipeline(dataService, graphingService);

                List<StockGraphState> allStocks = pipeline.processSectors(config.sectors, config.exchanges, 
                        (config.minMarketCap != null && !config.minMarketCap.isEmpty()) ? config.minMarketCap.get(0) : 50_000_000.0,
                        msg -> broadcast("PROGRESS", msg));

                broadcast("STATUS", "Simulating historical trades...");

                Simulation sim = new Simulation(params);
                List<StockTrade> allTrades = new ArrayList<>();
                int daysToBacktest = 100;

                for (StockGraphState s : allStocks) {
                    int days = s.closePrices().size();
                    int startIdx = Math.max(0, days - daysToBacktest - 30);

                    for (int i = startIdx + 30; i < days; i++) {
                        SimulationResult res = sim.calculateFastScore(new SimulationDataPackage(List.of(s)), 0, i);
                        if (res.heuristicScore() > 0.65) {
                            double buyPrice = s.closePrices().get(i);
                            double buyMA = StatsCalculator.calculateSlidingAvg(s.closePrices(), i, params.longMovingAvgTime(), s.stock().ticker_symbol());
                            if (buyMA == 0) continue;
                            double cutOff = (buyPrice / buyMA) * params.sellCutOffPerc();

                            for (int j = i + 1; j < days; j++) {
                                double currentPrice = s.closePrices().get(j);
                                double currentMA = StatsCalculator.calculateSlidingAvg(s.closePrices(), j, params.longMovingAvgTime(), s.stock().ticker_symbol());
                                if (currentMA == 0) break;
                                if (currentPrice < (currentMA * cutOff) || j == days - 1) {
                                    double gain = (currentPrice - buyPrice) / buyPrice;
                                    allTrades.add(new StockTrade(s.stock().ticker_symbol(), gain, days - i, j - i, buyPrice / buyMA, s.caps().get(i), s.dates().get(i)));
                                    i = j;
                                    break;
                                }
                            }
                        }
                    }
                }

                double totalGain = allTrades.stream().mapToDouble(StockTrade::getLastGained).sum() * 100;
                double avgGain = allTrades.isEmpty() ? 0 : totalGain / allTrades.size();

                Map<String, Object> report = Map.of(
                    "totalTrades", allTrades.size(),
                    "totalGain", String.format("%.2f%%", totalGain),
                    "avgGain", String.format("%.2f%%", avgGain),
                    "trades", allTrades.stream().sorted((a,b) -> b.getTicker().compareTo(a.getTicker())).limit(50).toList()
                );

                broadcast("BACKTEST_REPORT", report);
                broadcast("STATUS", "Backtest Complete.");

            } catch (Exception e) {
                logger.error("Backtest failed", e);
                broadcast("ERROR", "Backtest failed: " + e.getMessage());
            } finally {
                isRunning = false;
            }
        });
    }

    private void broadcast(String type, Object payload) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("payload", payload);
        message.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/updates", message);
    }

    public boolean isRunning() {
        return isRunning;
    }
}
