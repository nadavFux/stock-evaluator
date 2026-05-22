package com.stock.analyzer.infra;

import com.stock.analyzer.model.Profile;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {
    private final ProfileRepository profileRepository;

    public DataInitializer(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        profileRepository.deleteAll(); // Force update
        if (profileRepository.count() == 0) {
            String defaultConfig = """
                {
                    "startTimes": [50, 110, 200, 350],
                    "selectTimes": [30],
                    "searchTimes": [30, 80, 130],
                    "longMovingAvgTimes": [140],
                    "sellCutOffPerc": [0.93],
                    "lowerPriceToLongAvgBuyIn": [0.92],
                    "higherPriceToLongAvgBuyIn": [1.02],
                    "timeFrameForUpwardLongAvg": [40],
                    "timeFrameForOscillator": [110],
                    "maxPERatios": [25],
                    "aboveAvgRatingPricePerc": [1.0],
                    "timeFrameForUpwardShortPrice": [1],
                    "maxRSI": [100.0],
                    "minMarketCap": [50.0],
                    "maxMarketCap": [2000000.0],
                    "minRatesOfAvgInc": [1.1],
                    "minRatings": [3.75],
                    "maxRatings": [4.6],
                    "riskFreeRate": [0.05],
                    "sectors": [10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60],
                    "exchanges": ["TASE", "NYSE", "NasdaqGS", "NasdaqGM", "NasdaqCM"],
                    "outputPath": "output"
                }
                """;
            profileRepository.save(new Profile("Standard Balanced", "Default balanced strategy for large cap stocks", defaultConfig));

            String aggressiveConfig = """
                {
                    "startTimes": [30, 60, 90],
                    "selectTimes": [15],
                    "searchTimes": [20, 40],
                    "longMovingAvgTimes": [50],
                    "sellCutOffPerc": [0.90],
                    "lowerPriceToLongAvgBuyIn": [0.85],
                    "higherPriceToLongAvgBuyIn": [1.10],
                    "timeFrameForUpwardLongAvg": [20],
                    "timeFrameForOscillator": [60],
                    "maxPERatios": [50],
                    "aboveAvgRatingPricePerc": [1.2],
                    "timeFrameForUpwardShortPrice": [1],
                    "maxRSI": [100.0],
                    "minMarketCap": [500.0],
                    "maxMarketCap": [5000.0],
                    "minRatesOfAvgInc": [1.2],
                    "minRatings": [3.5],
                    "maxRatings": [5.0],
                    "riskFreeRate": [0.05],
                    "sectors": [10, 15, 45],
                    "exchanges": ["NYSE", "NasdaqGS"],
                    "outputPath": "output_aggressive"
                }
                """;
            profileRepository.save(new Profile("Aggressive Growth", "Focus on momentum and higher volatility", aggressiveConfig));
        }
    }
}
