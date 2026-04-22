package com.stock.analyzer.core;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class StatsCalculatorTest {

    @Test
    public void testCalculateRSI() {
        List<Double> prices = Arrays.asList(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28);
        double rsi = StatsCalculator.calculateRSI(prices, 14, 14);
        assertTrue(rsi >= 0 && rsi <= 100);
    }

    @Test
    public void testCalculateMACD() {
        List<Double> prices = Arrays.asList(
            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0,
            11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0,
            21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0
        );
        double macd = StatsCalculator.calculateMACD(prices, 26);
        assertNotNull(macd);
    }

    @Test
    public void testCalculateBollingerB() {
        List<Double> prices = Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0);
        double b = StatsCalculator.calculateBollingerB(prices, 10, 10, "TEST");
        assertTrue(b >= 0 && b <= 1);
    }

    @Test
    public void testCalculateATR() {
        List<Double> high = Arrays.asList(11.0, 12.0, 13.0, 14.0, 15.0);
        List<Double> low = Arrays.asList(9.0, 10.0, 11.0, 12.0, 13.0);
        List<Double> close = Arrays.asList(10.0, 11.0, 12.0, 13.0, 14.0);
        double atr = StatsCalculator.calculateATR(high, low, close, 4, 4);
        assertTrue(atr > 0);
    }
}
