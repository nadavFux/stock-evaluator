package project;

import common.DTO.ExecutionResult;
import common.StatsCalculator;
import temporal_workflows.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class Main {
    //72
    static final List<Integer> sectors = List.of(
            101010, 101020, // Energy
            151010, 151020, 151030, 151040, 151050, // Materials*/
            201010, 201020, 201030, 201040, 201050, 201060, 201070, 202010, 202020, 203010, 203020, 203030, 203040, 203050, // Industrials
            252010, 252020, 252030, 253010, 253020, 255010, 255030, 255040, // Consumer Discretionary
            301010, 302010, 302020, 302030, 303010, 303020,// Consumer Staples
            351010, 351020, 351030, 352010, 352020, 352030, // Healthcare
            401010, 402010, 402020, 402030, 402040, 403010, // Financials
            451020, 451030, 452010, 452020, 452030, 453010,// Information Technology
            501010, 501020, 502010, 502020, 502030, // Communication Services
            551010, 551020, 551030, 551040, 551050,// Utilities
            601010, 601025, 601030, 601040, 601050, 601060, 601070, 601080, 602010 // Real Estate*/
    );
    static final String queueName = "EntryTaskQueue";

    public static void main(String[] args) throws IOException {
        System.out.println("Starting Stock Analyzer...");

        // Initialize activities
        HashMap<String, String> headers = new HashMap<>();
        headers.put("api-key", "6a3a617f15f02e5302b849d18123bb5a32b3b0154ad2a3ddf55e7b5f66e39132");
        headers.put("date-format", "epoch");
        String baseURL = "https://api.bridgewise.com/v2/scanner?n=2000&gics={code}&last_n_days=1000&raw=true&metadata=true&score=true&price_equity=true&language=he-IL";
        FetchStockActivityImpl fetchStockActivity = new FetchStockActivityImpl(baseURL, "{code}", headers);

        List<String> exchanges = List.of("TASE", "NYSE", "NasdaqGS", "NasdaqGM", "NasdaqCM", "LSE", "TSE", "ENXTLS", "ENXTPA", "ENXTBR", "ARCA");
        FilterStockActivityImpl filterStockActivity = new FilterStockActivityImpl(exchanges, 50_000_000.0, 0);
        FilterNonStockActivityImpl filterNonStockActivity = new FilterNonStockActivityImpl();
        HashMap<String, String> identifyHeaders = new HashMap<>();
        identifyHeaders.put("Referer", "https://plus.tase.co.il/");
        identifyHeaders.put("Api-Key", "6a3a617f15f02e5302b849d18123bb5a32b3b0154ad2a3ddf55e7b5f66e39132");
        EnrichStockActivityImpl enrichStockActivity = new EnrichStockActivityImpl(
                "https://apipa.tase.co.il/tr/assets/",
                "https://api.bridgewise.com/v2/technical-analysis?identifier={id}&summary=true&language=he-IL&short_name=true",
                identifyHeaders,
                "IL-",
                identifyHeaders
        );

        GraphStockActivityImpl graphStockActivity = new GraphStockActivityImpl(
                "https://app.koyfin.com/api/v1/bfc/tickers/search",
                "https://app.koyfin.com/api/v3/data/graph?schema=packed",
                "https://app.koyfin.com/api/v3/data/keys",
                "{\"ids\":[{\"type\":\"KID\",\"id\":\"{KID}\"}],\"keys\":[{\"key\":\"fest_est_ar_strongbuy\"},{\"key\":\"fest_est_ar_outperform\"},{\"key\":\"fest_est_ar_hold\"},{\"key\":\"fest_est_ar_underperform\"},{\"key\":\"fest_est_ar_sell\"},{\"key\":\"fest_est_ar_avg_no\"}]}"
        );

        MichuCheckGraphStockImpl michuCheckGraphStock = new MichuCheckGraphStockImpl();
        DailyCheckGraphStockImpl dailyCheckGraphStock = new DailyCheckGraphStockImpl();

        // Create stock processor
        StockProcessor processor = new StockProcessor(
                fetchStockActivity,
                filterStockActivity,
                //filterNonStockActivity,
                enrichStockActivity,
                graphStockActivity,
                dailyCheckGraphStock
        );

        try {
            int batchSize = 3;
            for (int i = 0; i < sectors.size(); i += batchSize) {
                List<Integer> batch = sectors.subList(i, Math.min(i + batchSize, sectors.size()));

                // Process sectors in parallel
                List<CompletableFuture<ExecutionResult>> futures = batch.stream()
                        .map(sector -> CompletableFuture.supplyAsync(() -> processor.getStockData(sector)))
                        .toList();

                // Wait for all futures to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Collect results
                List<ExecutionResult> results = futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .toList();
            }
            // Calculate and write stats
            StatsCalculator.WriteStat();
            StatsCalculator.WriteStatEvals();
            System.out.println("Stock Analyzer completed successfully.");
        } finally {
            processor.shutdown();
        }
    }
}
