package com.stock.analyzer.service;

import com.stock.analyzer.model.SimulationRangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizerFactory {
    private static final Logger logger = LoggerFactory.getLogger(OptimizerFactory.class);

    public static Optimizer create(String type, SimulationRangeConfig config) {
        if (type == null) type = "cpu";
        
        logger.info("Creating optimizer of type: {}", type);
        
        switch (type.toLowerCase()) {
            case "tornadovm":
            case "gpu":
                if (TornadoVmOptimizer.isAvailable()) {
                    return new TornadoVmOptimizer(config);
                } else {
                    logger.warn("GPU Optimizer requested but TornadoVM is not functional. Falling back to CPU.");
                    return new CpuParamOptimizer(config);
                }
            case "cpu":
            default:
                return new CpuParamOptimizer(config);
        }
    }
}
