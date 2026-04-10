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
    private final ExecutorService cpuExecutor;

    public Pipeline(StockDataService dataService, GraphingService graphingService) {
        this.dataService = dataService;
        this.graphingService = graphingService;
        this.ioExecutor = Executors.newFixedThreadPool(20); // High concurrency for I/O
        this.cpuExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    public List<StockGraphState> processSectors(List<Integer> sectors, List<String> exchanges, double minCap, java.util.function.Consumer<String> progressCallback) {
        logger.info("Starting Parallel Pipeline for {} sectors...", sectors.size());
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);

        List<CompletableFuture<List<StockGraphState>>> sectorFutures = sectors.stream()
                .map(sector -> CompletableFuture.supplyAsync(() -> {
                            logger.info("starting for {}", sector);
                            List<BaseStock> bases = dataService.fetchAndFilter(sector, exchanges, minCap);
                            logger.info("fetched for {}", sector);
                            return bases;
                        }, ioExecutor)
                        .thenCompose(bases -> {
                            List<CompletableFuture<StockGraphState>> stockFutures = bases.stream()
                                    .map(base -> CompletableFuture.supplyAsync(() -> dataService.enrich(base), ioExecutor)
                                            .thenApplyAsync(enriched -> {
                                                if (enriched == null) return null;
                                                logger.info("further enrich for {}", enriched.ticker_symbol());
                                                return graphingService.fetchGraphState(enriched);
                                            }, ioExecutor))
                                    .toList();

                            return CompletableFuture.allOf(stockFutures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> {
                                        logger.info("finished indicators  for {}", sector);
                                        return stockFutures.stream()
                                                .map(CompletableFuture::join)
                                                .filter(Objects::nonNull)
                                                .toList();
                                    });
                        })
                        .thenApply(sectorResults -> {
                            int current = completed.incrementAndGet();
                            if (progressCallback != null) {
                                int percent = (int) ((current / (double) sectors.size()) * 100);
                                progressCallback.accept("Hydrating sectors: " + percent + "% (" + current + "/" + sectors.size() + ")");
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

    public void shutdown() {
        ioExecutor.shutdown();
        cpuExecutor.shutdown();
    }
}
