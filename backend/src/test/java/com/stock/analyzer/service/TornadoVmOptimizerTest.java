package com.stock.analyzer.service;

import com.stock.analyzer.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TornadoVmOptimizerTest {

    private SimulationRangeConfig config;

    @BeforeEach
    public void setup() {
        config = new SimulationRangeConfig();
        config.sellCutOffPerc = new ArrayList<>(List.of(0.99));
        config.lowerPriceToLongAvgBuyIn = new ArrayList<>(List.of(0.50));
        config.higherPriceToLongAvgBuyIn = new ArrayList<>(List.of(1.50));
        config.timeFrameForUpwardLongAvg = new ArrayList<>(List.of(20));
        config.aboveAvgRatingPricePerc = new ArrayList<>(List.of(1.5));
        config.timeFrameForUpwardShortPrice = new ArrayList<>(List.of(5));
        config.timeFrameForOscillator = new ArrayList<>(List.of(14));
        config.maxRSI = new ArrayList<>(List.of(80.0));
        config.minMarketCap = new ArrayList<>(List.of(1000.0));
        config.longMovingAvgTimes = new ArrayList<>(List.of(100));
        config.minRatesOfAvgInc = new ArrayList<>(List.of(0.1));
        config.maxPERatios = new ArrayList<>(List.of(90));
        config.minRatings = new ArrayList<>(List.of(1.0));
        config.maxRatings = new ArrayList<>(List.of(5.0));
        config.maxMarketCap = new ArrayList<>(List.of(100000000000.0));
        config.riskFreeRate = new ArrayList<>(List.of(0.05));
        config.buyThreshold = new ArrayList<>(List.of(0.2));
        config.startTimes = List.of(20);
        config.searchTimes = List.of(20);
        config.selectTimes = List.of(1);
    }

    @Test
    public void testIsAvailable_Sanity() {
        assertDoesNotThrow(() -> {
            boolean available = TornadoVmOptimizer.isAvailable();
            System.out.println("TornadoVM available: " + available);
        });
    }

    public static List<StockGraphState> generateSyntheticStocks(int count, int days) {
        List<StockGraphState> stocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ticker = "TEST" + i;
            List<Double> prices = new ArrayList<>();
            List<Double> volumes = new ArrayList<>();
            List<Double> ratings = new ArrayList<>();
            List<Double> epss = new ArrayList<>();
            List<Double> caps = new ArrayList<>();
            List<String> dates = new ArrayList<>();

            double currentPrice = 100.0;
            for (int d = 0; d < days; d++) {
                currentPrice *= (1.0 + (Math.random() * 0.002 - 0.001));
                if (d > days / 2 && d < days / 2 + 10) currentPrice = 50.0; // Artificial dip
                prices.add(currentPrice);
                volumes.add(1000000.0);
                ratings.add(4.5);
                epss.add(5.0);
                caps.add(1000000000.0);
                dates.add("2023-01-01");
            }
            stocks.add(new StockGraphState(new Stock("1", ticker, ticker, "NYSE", "2023", 0.1f, 0.0, 0.0, "id", "sector", 0.0),
                    ratings, 0.0, 0.0, 0.0, prices, volumes, new ArrayList<>(), dates, epss, caps));
        }
        return stocks;
    }
}
