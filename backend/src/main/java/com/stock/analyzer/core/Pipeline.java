package com.stock.analyzer.core;

import com.stock.analyzer.model.BaseStock;
import com.stock.analyzer.model.StockGraphState;
import com.stock.analyzer.service.GraphingService;
import com.stock.analyzer.service.StockDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Pipeline implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Pipeline.class);
    private final StockDataService dataService;
    private final GraphingService graphingService;
    private final ExecutorService ioExecutor;
    private final Semaphore apiLimit;

    public Pipeline(StockDataService dataService, GraphingService graphingService) {
        this.dataService = dataService;
        this.graphingService = graphingService;
        this.ioExecutor = Executors.newFixedThreadPool(32); 
        this.apiLimit = new Semaphore(25); // Hard limit on concurrent network calls
    }

    public List<StockGraphState> processSectors(List<Integer> sectors, List<String> exchanges, double minCap, java.util.function.Consumer<String> progressCallback) {
        logger.info("Starting Parallel Pipeline for {} sectors...", sectors.size());
        
        List<CompletableFuture<List<StockGraphState>>> sectorFutures = sectors.stream()
                .map(sector -> processSector(sector, exchanges, minCap, progressCallback))
                .toList();

        // BLOCK until ALL sectors are fully finished
        return CompletableFuture.allOf(sectorFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> sectorFutures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()))
                .join();
    }

    private CompletableFuture<List<StockGraphState>> processSector(int sector, List<String> exchanges, double minCap, java.util.function.Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                apiLimit.acquire();
                if (progressCallback != null) progressCallback.accept("Scanning sector " + sector + "...");
                return dataService.fetchAndFilter(sector, exchanges, minCap);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ArrayList<BaseStock>();
            } finally {
                apiLimit.release();
            }
        }, ioExecutor).thenCompose(bases -> {
            if (bases.isEmpty()) return CompletableFuture.completedFuture(new ArrayList<>());
            
            java.util.concurrent.atomic.AtomicInteger sectorProcessed = new java.util.concurrent.atomic.AtomicInteger(0);
            List<CompletableFuture<StockGraphState>> stockFutures = bases.stream()
                    .map(base -> processStock(base, sector, sectorProcessed, bases.size(), progressCallback))
                    .toList();

            return CompletableFuture.allOf(stockFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> stockFutures.stream()
                            .map(f -> {
                                try {
                                    return f.get(); // Safe because of allOf
                                } catch (Exception e) {
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .toList());
        });
    }

    private CompletableFuture<StockGraphState> processStock(BaseStock base, int sector, java.util.concurrent.atomic.AtomicInteger processed, int total, java.util.function.Consumer<String> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. Check Cache First (Zero cost)
            StockGraphState cached = graphingService.getCachedState(base.ticker_symbol());
            if (cached != null) {
                updateProgress(sector, processed, total, progressCallback);
                return cached;
            }

            // 2. Acquisition (Limited by Semaphore)
            try {
                apiLimit.acquire();
                com.stock.analyzer.model.Stock enriched = dataService.enrich(base);
                if (enriched == null) return null;

                StockGraphState graph = graphingService.fetchGraphState(enriched);
                updateProgress(sector, processed, total, progressCallback);
                return graph;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                logger.error("Failed to hydrate {}: {}", base.ticker_symbol(), e.getMessage());
                return null;
            } finally {
                apiLimit.release();
            }
        }, ioExecutor);
    }

    private void updateProgress(int sector, java.util.concurrent.atomic.AtomicInteger processed, int total, java.util.function.Consumer<String> callback) {
        int count = processed.incrementAndGet();
        if (callback != null && (count % 10 == 0 || count == total)) {
            callback.accept(String.format("Sector %d: %d/%d stocks hydrated", sector, count, total));
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down Pipeline executor...");
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        close();
    }
}
