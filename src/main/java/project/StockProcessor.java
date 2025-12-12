package project;

import common.DTO.BaseStock;
import common.DTO.ExecutionResult;
import common.DTO.Stock;
import common.DTO.StockGraphState;
import temporal_workflows.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static common.FileHelper.ReadResults;
import static common.FileHelper.SaveResults;

public class StockProcessor {
    private final FetchStockActivity fetchStockActivity;
    private final FilterStockActivity filterStockActivity;
    private final EnrichStockActivity enrichStockActivity;
    private final GraphStockActivity graphStockActivity;
    private final CheckGraphStock checkGraphStock;
    private final ExecutorService executorService;

    public StockProcessor(FetchStockActivity fetchStockActivity,
                          FilterStockActivity filterStockActivity,
                          EnrichStockActivity enrichStockActivity,
                          GraphStockActivity graphStockActivity,
                          CheckGraphStock checkGraphStock) {
        this.fetchStockActivity = fetchStockActivity;
        this.filterStockActivity = filterStockActivity;
        this.enrichStockActivity = enrichStockActivity;
        this.graphStockActivity = graphStockActivity;
        this.checkGraphStock = checkGraphStock;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    public ExecutionResult getStockData(int sector) {
        try {
            List<StockGraphState> finalist;
            File f = new File("cache_" + sector + ".txt");
            if (f.exists() && !f.isDirectory()) {
                finalist = ReadResults("cache_" + sector + ".txt");
            } else {
                var fetched = this.fetchStockActivity.fetchStockData(sector);
                var stocks = this.filterStockActivity.filterStockData(fetched);

                // Process stocks in batches using CompletableFuture
                int batchSize = 10;
                List<Stock> results = new ArrayList<>();
                for (int i = 0; i < stocks.size(); i += batchSize) {
                    List<BaseStock> batch = stocks.subList(i, Math.min(i + batchSize, stocks.size()));

                    // Create a list of CompletableFuture for the current batch
                    List<CompletableFuture<Stock>> futures = batch.stream()
                            .map(stock -> CompletableFuture.supplyAsync(
                                    () -> this.enrichStockActivity.enrichStockData(stock),
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
                                    () -> this.graphStockActivity.graphStockData(stock),
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
                    .map(this.checkGraphStock::checkStockData)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            return new ExecutionResult(checkedStocks,
                    checkedStocks.stream().filter(res -> Objects.nonNull(res.result())).mapToDouble(res -> res.result().getEval()).sum());

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