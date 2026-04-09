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

@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);
    private final SimpMessagingTemplate messagingTemplate;
    private boolean isRunning = false;

    public AnalysisService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
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
                HttpClientService httpClient = new HttpClientService(15);
                
                Map<String, String> authHeaders = new HashMap<>();
                authHeaders.put("api-key", "6a3a617f15f02e5302b849d18123bb5a32b3b0154ad2a3ddf55e7b5f66e39132");
                authHeaders.put("date-format", "epoch");
                authHeaders.put("Referer", "https://plus.tase.co.il/");

                StockDataService dataService = new StockDataService(httpClient, 
                        "https://api.bridgewise.com/v2/scanner?n=4000&gics={code}&last_n_days=1000&raw=true&metadata=true&score=true&price_equity=true&language=he-IL",
                        "https://apipa.tase.co.il/tr/assets/",
                        "https://api.bridgewise.com/v2/technical-analysis?identifier={id}&summary=true&language=he-IL&short_name=true",
                        authHeaders);

                GraphingService graphingService = new GraphingService(httpClient,
                        "https://app.koyfin.com/api/v1/bfc/tickers/search",
                        "https://app.koyfin.com/api/v3/data/graph?schema=packed");

                Pipeline pipeline = new Pipeline(dataService, graphingService);

                broadcast("PROGRESS", "Hydrating stocks...");
                List<StockGraphState> allStocks = pipeline.processSectors(config.sectors, config.exchanges, 50_000_000.0, 
                        msg -> broadcast("PROGRESS", msg));
                broadcast("STATUS", "Collected data for " + allStocks.size() + " stocks.");

                broadcast("PROGRESS", "Optimizing parameters...");
                ParamOptimizer optimizer = new ParamOptimizer(config);
                SimulationParams bestParams = optimizer.optimize(allStocks);

                broadcast("PROGRESS", "Training AI model...");
                MLModelService mlService = optimizer.getMlService();
                mlService.train();
                mlService.saveSamples(config.outputPath + File.separator + "training_samples.csv");
                broadcast("ML_FEATURES", mlService.getFeatureImportance());

                broadcast("PROGRESS", "Generating recommendations...");
                Simulation inferenceSim = new Simulation(bestParams);
                inferenceSim.setMLService(mlService);
                
                List<StockCheckResult> recommendations = allStocks.stream()
                        .map(stock -> {
                            List<Double> movingAvg = StatsCalculator.MovingAvg(stock, bestParams.longMovingAvgTime());
                            SimulationResult res = inferenceSim.calculateDualScore(stock.closePrices(), movingAvg, stock.closePrices().size() - 1, stock.stock(), stock.epss(), stock.rating(), stock.caps(), stock.volumes());
                            
                            List<TradePoint> tradePoints = new ArrayList<>();
                            // If heuristic is high, mark as a "Current Buy Signal"
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
