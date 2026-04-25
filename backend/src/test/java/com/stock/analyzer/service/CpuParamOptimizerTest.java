package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import com.stock.analyzer.core.Simulation;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

public class CpuParamOptimizerTest {

    @Test
    public void testCandidateEvaluation() {
        SimulationParams params = createDefaultParams();
        Simulation sim = new Simulation(params);
        
        // Add dummy trades
        sim.recordTrade(0.05, 10);
        sim.recordTrade(0.10, 20);
        
        double score = sim.calculateScore(1);
        assertTrue(score > 0, "Score should be positive for profitable trades");
    }

    @Test
    public void testRandomizeRespectsLogicalBounds() {
        CpuParamOptimizer optimizer = new CpuParamOptimizer(new SimulationRangeConfig());
        SimulationParams center = createDefaultParams();
        
        for (int i = 0; i < 50; i++) {
            SimulationParams p = optimizer.randomize(center, 0.2);
            assertWithin(p.sellCutOffPerc(), 0.1, 0.99, "sellCutOffPerc");
            assertWithin(p.buyThreshold(), 0.4, 0.95, "buyThreshold");
            assertTrue(p.longMovingAvgTime() >= 10, "longMovingAvgTime too small");
        }
    }

    @Test
    public void testLinearVolumeScaling() {
        SimulationParams params = createDefaultParams();
        
        // Sim 1: Low trade density
        Simulation sim1 = new Simulation(params);
        sim1.recordTrade(0.1, 10);
        
        // Sim 2: High trade density
        Simulation sim2 = new Simulation(params);
        for (int i = 0; i < 10; i++) sim2.recordTrade(0.1, 10);
        
        double score1 = sim1.calculateScore(5);
        double score2 = sim2.calculateScore(5);
        
        assertTrue(score2 > score1, "Higher density should result in a higher score");
    }

    private void assertWithin(double val, double min, double max, String field) {
        assertTrue(val >= min && val <= max, 
            String.format("Field %s: Value %.2f not within [%.2f, %.2f]", field, val, min, max));
    }

    private SimulationParams createDefaultParams() {
        return new SimulationParams(
            0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0, 0.65,
            0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }
}
