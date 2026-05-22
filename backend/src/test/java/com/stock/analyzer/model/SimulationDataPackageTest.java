package com.stock.analyzer.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

public class SimulationDataPackageTest {

    private Stock createDummyStock(String ticker) {
        return new Stock("id", "name", ticker, "exchange", "2023-01-01", 1000f, 1.0, 1.0, "ident", "other", 1.0);
    }

    private StockGraphState createDummyStockState(Stock stock, List<Double> closePrices, List<Double> dates) {
        int size = closePrices.size();
        List<Double> ones = java.util.Collections.nCopies(size, 1.0);
        List<String> dateStrs = dates != null ? dates.stream().map(Object::toString).toList() : java.util.Collections.nCopies(size, "date");
        
        return new StockGraphState(stock, ones, 0.0, 0.0, 0.0, closePrices, ones, ones, dateStrs, ones, ones);
    }

    @Test
    public void testGetAvg() {
        Stock s1 = createDummyStock("AAPL");
        
        // Price: [10, 20, 30, 40, 50]
        StockGraphState stock = createDummyStockState(s1, Arrays.asList(10.0, 20.0, 30.0, 40.0, 50.0), null);

        SimulationDataPackage pkg = new SimulationDataPackage(List.of(stock));

        // Test 3-day average at day 4 (index 3)
        // (20 + 30 + 40) / 3 = 30.0
        assertEquals(30.0, pkg.getAvg(0, 3, 3), 0.001);
        
        // Test 5-day average at day 5 (index 4)
        // (10 + 20 + 30 + 40 + 50) / 5 = 30.0
        assertEquals(30.0, pkg.getAvg(0, 4, 5), 0.001);
    }

    @Test
    public void testGetVolatility() {
         Stock s1 = createDummyStock("AAPL");
         
         // Price: [10, 10, 10, 10, 10] -> Vol should be 0
         StockGraphState stock = createDummyStockState(s1, Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0), null);

         SimulationDataPackage pkg = new SimulationDataPackage(List.of(stock));
         assertEquals(0.0, pkg.getVolatility(0, 4, 5), 0.001);
    }
}
