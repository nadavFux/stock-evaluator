package com.stock.analyzer.api;

import com.stock.analyzer.model.StockGraphState;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.BaseStock;
import com.stock.analyzer.service.GraphingService;
import com.stock.analyzer.service.StockDataService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/stocks")
public class StockController {
    private final GraphingService graphingService;
    private final StockDataService dataService;

    public StockController(GraphingService graphingService, StockDataService dataService) {
        this.graphingService = graphingService;
        this.dataService = dataService;
    }

    @GetMapping("/{ticker}/graph")
    public StockGraphState getGraph(@PathVariable String ticker) {
        // We need a basic Stock object to fetch graph state.
        // For simplicity, we'll create a dummy BaseStock and enrich it.
        BaseStock base = new BaseStock("0", "", ticker, "NYSE", "", 0.0f, 0.0, 0.0);
        Stock enriched = dataService.enrich(base);
        if (enriched == null) {
             // Try Nasdaq if NYSE fails
             base = new BaseStock("0", "", ticker, "NasdaqGS", "", 0.0f, 0.0, 0.0);
             enriched = dataService.enrich(base);
        }
        
        if (enriched != null) {
            return graphingService.fetchGraphState(enriched);
        }
        return null;
    }
}
