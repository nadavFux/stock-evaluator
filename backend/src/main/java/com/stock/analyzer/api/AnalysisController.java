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
    public Map<String, String> run(@RequestBody @Valid SimulationRangeConfig config) {
        analysisService.runAnalysis(config);
        return Map.of("message", "Analysis started");
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
