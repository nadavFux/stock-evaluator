package com.stock.analyzer.api;

import com.stock.analyzer.model.SectorPerformance;
import com.stock.analyzer.service.StockDataService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sectors")
public class SectorController {
    private final StockDataService dataService;

    public SectorController(StockDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/performance")
    public List<SectorPerformance> getPerformance(@RequestParam List<Integer> sectors, @RequestParam List<String> exchanges) {
        return sectors.stream()
                .map(sector -> dataService.calculateSectorPerformance(sector, exchanges))
                .collect(Collectors.toList());
    }
}
