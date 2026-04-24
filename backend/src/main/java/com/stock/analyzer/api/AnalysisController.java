package com.stock.analyzer.api;

import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.service.AnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@CrossOrigin(origins = "*")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/run")
    public Map<String, String> runAnalysis(@RequestBody @Valid SimulationRangeConfig config) {
        analysisService.runAnalysis(config);
        return Map.of("status", "Analysis started");
    }

    @PostMapping("/backtest")
    public Map<String, String> runBacktest(@RequestBody SimulationRangeConfig config) {
        try {
            analysisService.runBacktest(config);
            return Map.of("status", "Backtest started");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/opportunities")
    public Map<String, String> runOpportunities(@RequestBody SimulationRangeConfig config) {
        try {
            analysisService.runOpportunities(config);
            return Map.of("status", "Opportunities started");
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("isRunning", analysisService.isRunning());
    }

    @GetMapping("/export-params")
    public Map<String, String> exportParams() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("C:/Users/NF/IdeaProjects/stock-analyzer-java/output/best_params.yaml");
            if (java.nio.file.Files.exists(path)) {
                String content = java.nio.file.Files.readString(path);
                return Map.of("content", content);
            }
            return Map.of("error", "best_params.yaml not found. Run analysis first.");
        } catch (Exception e) {
            return Map.of("error", "Failed to read params: " + e.getMessage());
        }
    }
}
