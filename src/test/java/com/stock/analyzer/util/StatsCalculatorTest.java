package com.stock.analyzer.util;

import com.stock.analyzer.model.dto.StockGraphState;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class StatsCalculatorTest {

    @Test
    public void testCalculateSlidingAvg() {
        List<Double> prices = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        String key = "testKey";
        // Average of 30, 40, 50 (last 3 elements ending at index 4)
        double avg = StatsCalculator.calculateSlidingAvg(prices, 4, 3, key);
        assertEquals(40.0, avg, 0.001);
    }

    @Test
    public void testFindLowestAndHighest() {
        List<Double> prices = Arrays.asList(10.0, 5.0, 20.0, 2.0, 8.0, 15.0);
        int[] result = StatsCalculator.findLowestAndHighest(prices, 0, 6);
        // Lowest is 2.0 at index 3, highest is 20.0 at index 2
        assertEquals(3, result[0]);
        assertEquals(2, result[1]);
    }

    @Test
    public void testCalculateRSI() {
        // Create a list of price changes that will result in a known RSI.
        // For simplicity, let's just test that it runs and returns a valid range value
        // (0-100)
        // because setting up exactly N periods requires a bit of data.
        Double[] pricesArray = new Double[20];
        for (int i = 0; i < 20; i++)
            pricesArray[i] = (double) (100 + i); // Upward trend
        List<Double> prices = Arrays.asList(pricesArray);

        String key = "rsiKey";
        double rsi = StatsCalculator.calculateRSI(prices, key);
        assertTrue(rsi >= 0 && rsi <= 100);
        // Pure upward trend implies RSI 100
        assertEquals(100.0, rsi, 0.001);
    }

    @Test
    public void testMovingAvg() {
        List<Double> prices = Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0);
        StockGraphState mockStock = new StockGraphState(
                null, // Stock
                null, // rating
                0.0, // low
                0.0, // high
                0.0, // price
                prices, // closePrices
                null, // volumes
                null, // avgs
                null, // dates
                null, // epss
                null // caps
        );

        List<Double> movingAverages = StatsCalculator.MovingAvg(mockStock, 3);
        assertEquals(5, movingAverages.size());
        assertNull(movingAverages.get(0));
        assertNull(movingAverages.get(1));
        assertNull(movingAverages.get(2));
        // Index 3: average of 0, 1, 2 (10, 20, 30) -> 20.0
        assertEquals(20.0, movingAverages.get(3), 0.001);
        // Index 4: average of 1, 2, 3 (20, 30, 40) -> 30.0
        assertEquals(30.0, movingAverages.get(4), 0.001);
    }
}
