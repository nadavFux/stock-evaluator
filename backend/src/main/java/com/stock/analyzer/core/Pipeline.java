package com.stock.analyzer.core;

import com.stock.analyzer.model.BaseStock;
import com.stock.analyzer.model.StockGraphState;
import com.stock.analyzer.service.GraphingService;
import com.stock.analyzer.service.StockDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Pipeline {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);
    private final StockDataService dataService;
    private final GraphingService graphingService;
    private final ExecutorService ioExecutor;

    public Pipeline(StockDataService dataService, GraphingService graphingService) {
        this.dataService = dataService;
        this.graphingService = graphingService;
        this.ioExecutor = Executors.newFixedThreadPool(20); // High concurrency for I/O
    }

    public List<StockGraphState> processSectors(List<Integer> sectors, List<String> exchanges, double minCap, java.util.function.Consumer<String> progressCallback) {
        logger.info("Starting Parallel Pipeline for {} sectors...", sectors.size());
        java.util.concurrent.atomic.AtomicInteger completedSectors = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalStocksFound = new java.util.concurrent.atomic.AtomicInteger(0);

        List<CompletableFuture<List<StockGraphState>>> sectorFutures = sectors.stream()
                .map(sector -> CompletableFuture.supplyAsync(() -> {
                            if (progressCallback != null) progressCallback.accept("Scanning sector " + sector + "...");
                            List<BaseStock> bases = dataService.fetchAndFilter(sector, exchanges, minCap);
                            totalStocksFound.addAndGet(bases.size());
                            logger.info("Sector {}: Found {} stocks", sector, bases.size());
                            return bases;
                        }, ioExecutor)
                        .thenCompose(bases -> {
                            if (bases.isEmpty()) return CompletableFuture.completedFuture(java.util.Collections.<StockGraphState>emptyList());
                            
                            java.util.concurrent.atomic.AtomicInteger sectorProcessed = new java.util.concurrent.atomic.AtomicInteger(0);
                            List<CompletableFuture<StockGraphState>> stockFutures = bases.stream()
                                    .map(base -> CompletableFuture.supplyAsync(() -> {
                                                // 1. Check Cache First (Skip both enrichment and fetch if hit)
                                                StockGraphState cached = graphingService.getCachedState(base.ticker_symbol());
                                                if (cached != null) {
                                                    updateProgress(sector, sectorProcessed, bases.size(), progressCallback);
                                                    return cached;
                                                }

                                                // 2. Cache Miss: Full Enrichment + Fetch
                                                com.stock.analyzer.model.Stock enriched = dataService.enrich(base);
                                                if (enriched == null) return null;

                                                StockGraphState graph = graphingService.fetchGraphState(enriched);
                                                updateProgress(sector, sectorProcessed, bases.size(), progressCallback);
                                                return graph;
                                            }, ioExecutor))
                                    .toList();

                            return CompletableFuture.allOf(stockFutures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> stockFutures.stream()
                                            .map(CompletableFuture::join)
                                            .filter(Objects::nonNull)
                                            .toList());
                        })
                        .thenApply(sectorResults -> {
                            int current = completedSectors.incrementAndGet();
                            if (progressCallback != null) {
                                int percent = (int) ((current / (double) sectors.size()) * 100);
                                progressCallback.accept(String.format("Global Progress: %d%% (%d/%d sectors). Total stocks so far: %d", 
                                        percent, current, sectors.size(), totalStocksFound.get()));
                            }
                            return sectorResults;
                        }))
                .toList();

        return CompletableFuture.allOf(sectorFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> sectorFutures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()))
                .join();
    }

    private void updateProgress(int sector, java.util.concurrent.atomic.AtomicInteger processed, int total, java.util.function.Consumer<String> callback) {
        int count = processed.incrementAndGet();
        if (callback != null && (count % 10 == 0 || count == total)) {
            callback.accept(String.format("Sector %d: %d/%d stocks hydrated", sector, count, total));
        }
    }

    public void shutdown() {
        ioExecutor.shutdown();
    }
}
