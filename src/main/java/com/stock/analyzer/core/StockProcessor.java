package com.stock.analyzer.core;

import com.stock.analyzer.model.dto.BaseStock;
import com.stock.analyzer.model.dto.ExecutionResult;
import com.stock.analyzer.model.dto.Stock;
import com.stock.analyzer.model.dto.StockGraphState;
import com.stock.analyzer.service.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.stock.analyzer.util.FileHelper.ReadResults;
import static com.stock.analyzer.util.FileHelper.SaveResults;

public class StockProcessor {
    private final StockFetcher stockFetcher;
    private final StockFilter stockFilter;
    private final StockEnricher stockEnricher;
    private final StockGraphService stockGraphService;
    private final StockAnalysisStrategy stockAnalysisStrategy;
    private final ExecutorService executorService;

    public StockProcessor(StockFetcher stockFetcher,
            StockFilter stockFilter,
            StockEnricher stockEnricher,
            StockGraphService stockGraphService,
            StockAnalysisStrategy stockAnalysisStrategy) {
        this.stockFetcher = stockFetcher;
        this.stockFilter = stockFilter;
        this.stockEnricher = stockEnricher;
        this.stockGraphService = stockGraphService;
        this.stockAnalysisStrategy = stockAnalysisStrategy;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    public ExecutionResult getStockData(int sector) {
        try {
            List<StockGraphState> finalist;
            File f = new File("cache_" + sector + ".txt");
            if (f.exists() && !f.isDirectory()) {
                finalist = ReadResults("cache_" + sector + ".txt");
            } else {
                var fetched = this.stockFetcher.fetchStockData(sector);
                var stocks = this.stockFilter.filterStockData(fetched);

                // Process stocks in batches using CompletableFuture
                int batchSize = 10;
                List<Stock> results = new ArrayList<>();
                for (int i = 0; i < stocks.size(); i += batchSize) {
                    List<BaseStock> batch = stocks.subList(i, Math.min(i + batchSize, stocks.size()));

                    // Create a list of CompletableFuture for the current batch
                    List<CompletableFuture<Stock>> futures = batch.stream()
                            .map(stock -> CompletableFuture.supplyAsync(
                                    () -> this.stockEnricher.enrichStockData(stock),
                                    executorService))
                            .collect(Collectors.toList());

                    // Wait for all futures in the batch to complete and collect results
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    results.addAll(futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
                }

                // Process enriched stocks in batches
                batchSize = 3;
                finalist = new ArrayList<>();
                for (int i = 0; i < results.size(); i += batchSize) {
                    List<Stock> batch = results.subList(i, Math.min(i + batchSize, results.size()));

                    // Create a list of CompletableFuture for the current batch
                    List<CompletableFuture<StockGraphState>> futures = batch.stream()
                            .map(stock -> CompletableFuture.supplyAsync(
                                    () -> this.stockGraphService.graphStockData(stock),
                                    executorService))
                            .collect(Collectors.toList());

                    // Wait for all futures in the batch to complete and collect results
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    finalist.addAll(futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
                }
                SaveResults(finalist, "cache_" + sector + ".txt");
            }

            // Process final results
            var checkedStocks = finalist.stream()
                    .map(this.stockAnalysisStrategy::checkStockData)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return new ExecutionResult(checkedStocks,
                    checkedStocks.stream().filter(res -> Objects.nonNull(res.result()))
                            .mapToDouble(res -> res.result().getEval()).sum());

        } catch (Exception e) {
            System.out.println("Error processing stock data: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
