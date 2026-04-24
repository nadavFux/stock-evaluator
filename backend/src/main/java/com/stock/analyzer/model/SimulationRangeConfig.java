package com.stock.analyzer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;

public class SimulationRangeConfig {
    private static final Logger logger = LoggerFactory.getLogger(SimulationRangeConfig.class);

    @NotEmpty
    public List<Integer> startTimes;
    @NotEmpty
    public List<Integer> selectTimes;
    @NotEmpty
    public List<Integer> searchTimes;
    @NotEmpty
    public List<Integer> longMovingAvgTimes;
    @NotEmpty
    public List<Double> sellCutOffPerc;
    @NotEmpty
    public List<Double> lowerPriceToLongAvgBuyIn;
    @NotEmpty
    public List<Double> higherPriceToLongAvgBuyIn;
    @NotEmpty
    public List<Integer> timeFrameForUpwardLongAvg;
    @NotEmpty
    public List<Integer> timeFrameForOscillator;
    @NotEmpty
    public List<Integer> maxPERatios;
    @NotEmpty
    public List<Double> aboveAvgRatingPricePerc;
    @NotEmpty
    public List<Integer> timeFrameForUpwardShortPrice;
    @NotEmpty
    public List<Double> maxRSI;
    @NotEmpty
    public List<Double> minMarketCap;
    @NotEmpty
    public List<Double> maxMarketCap;
    @NotEmpty
    public List<Double> minRatesOfAvgInc;
    @NotEmpty
    public List<Double> minRatings;
    @NotEmpty
    public List<Double> maxRatings;
    public List<Double> buyThreshold;
    @NotEmpty
    public List<Double> riskFreeRate;
    @NotEmpty
    public List<Integer> sectors;
    @NotEmpty
    public List<String> exchanges;
    @NotNull
    public String outputPath;

    // Weight Ranges
    public List<Double> movingAvgGapWeight;
    public List<Double> reversionToMeanWeight;
    public List<Double> ratingWeight;
    public List<Double> upwardIncRateWeight;
    public List<Double> rvolWeight;
    public List<Double> pegWeight;
    public List<Double> volatilityCompressionWeight;

    public static SimulationRangeConfig load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = SimulationRangeConfig.class.getResourceAsStream("/config.yaml")) {
            if (is == null) {
                throw new RuntimeException("Could not find config.yaml in resources");
            }
            return mapper.readValue(is, SimulationRangeConfig.class);
        } catch (Exception e) {
            logger.error("Failed to load configuration from YAML", e);
            throw new RuntimeException(e);
        }
    }
}
