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
                return new TornadoVmOptimizer(config);
            case "cpu":
            default:
                return new CpuParamOptimizer(config);
        }
    }
}
