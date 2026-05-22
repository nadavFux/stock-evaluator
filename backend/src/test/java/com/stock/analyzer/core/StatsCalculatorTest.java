package com.stock.analyzer.core;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class StatsCalculatorTest {

    @Test
    public void testSlidingAvg() {
        List<Double> prices = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        double avg = StatsCalculator.calculateSlidingAvg(prices, 4, 3, "TEST");
        assertEquals(40.0, avg, 0.01, "Avg of 30, 40, 50 should be 40");
    }

    @Test
    public void testVolatility() {
        List<Double> prices = Arrays.asList(100.0, 100.0, 100.0, 100.0);
        double vol = StatsCalculator.calculateVolatility(prices, 3, 4, "TEST");
        assertEquals(0.0, vol, 0.01, "Volatility of constant prices should be 0");
    }

    @Test
    public void testRSI() {
        List<Double> prices = Arrays.asList(100.0, 105.0, 110.0, 115.0, 120.0, 125.0, 130.0, 135.0, 140.0, 145.0, 150.0);
        double rsi = StatsCalculator.calculateRSI(prices, 10, 5);
        assertTrue(rsi > 50, "RSI should be high for uptrend");
    }
}
