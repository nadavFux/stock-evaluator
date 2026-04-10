package com.stock.analyzer.model;

import java.util.List;

public class SimulationDataPackage {
    public final double[][] closePrices;
    public final double[][] volumes;
    public final double[][] ratings;
    public final double[][] epss;
    public final double[][] caps;
    public final double[][] pricePrefixSum;
    public final double[][] priceSqPrefixSum;
    public final String[] tickers;
    public final String[][] dates;
    public final int stockCount;
    public final int daysCount;

    public SimulationDataPackage(List<StockGraphState> stocks) {
        this.stockCount = stocks.size();
        int maxDays = 0;
        for (StockGraphState s : stocks) {
            maxDays = Math.max(maxDays, s.closePrices().size());
        }
        this.daysCount = maxDays;
        this.tickers = new String[stockCount];
        this.dates = new String[stockCount][daysCount];
        this.closePrices = new double[stockCount][daysCount];
        this.volumes = new double[stockCount][daysCount];
        this.ratings = new double[stockCount][daysCount];
        this.epss = new double[stockCount][daysCount];
        this.caps = new double[stockCount][daysCount];
        this.pricePrefixSum = new double[stockCount][daysCount];
        this.priceSqPrefixSum = new double[stockCount][daysCount];

        for (int i = 0; i < stockCount; i++) {
            StockGraphState s = stocks.get(i);
            tickers[i] = s.stock().ticker_symbol();
            int currentSize = s.closePrices().size();
            int offset = daysCount - currentSize;
            
            double runningSum = 0;
            double runningSqSum = 0;
            
            // Fill padded zeros for initial indices if any
            for (int j = 0; j < offset; j++) {
                closePrices[i][j] = 0;
                volumes[i][j] = 0;
                ratings[i][j] = 0;
                epss[i][j] = 0;
                caps[i][j] = 0;
                dates[i][j] = null;
                pricePrefixSum[i][j] = 0;
                priceSqPrefixSum[i][j] = 0;
            }

            for (int j = 0; j < currentSize; j++) {
                int targetIdx = j + offset;
                Double pValue = s.closePrices().get(j);
                double p = (pValue != null) ? pValue : 0.0;
                
                closePrices[i][targetIdx] = p;
                volumes[i][targetIdx] = s.volumes().get(j) != null ? s.volumes().get(j) : 0.0;
                ratings[i][targetIdx] = s.rating().get(j) != null ? s.rating().get(j) : 0.0;
                epss[i][targetIdx] = s.epss().get(j) != null ? s.epss().get(j) : 0.0;
                caps[i][targetIdx] = s.caps().get(j) != null ? s.caps().get(j) : 0.0;
                dates[i][targetIdx] = s.dates().get(j);

                runningSum += p;
                runningSqSum += (p * p);
                pricePrefixSum[i][targetIdx] = runningSum;
                priceSqPrefixSum[i][targetIdx] = runningSqSum;
            }
        }
    }

    public double getAvg(int stockIdx, int dayIdx, int period) {
        if (dayIdx < 0) return 0.0;
        if (dayIdx < period) {
            return pricePrefixSum[stockIdx][dayIdx] / (dayIdx + 1);
        }
        double sum = pricePrefixSum[stockIdx][dayIdx] - pricePrefixSum[stockIdx][dayIdx - period];
        return sum / period;
    }

    public double getVolatility(int stockIdx, int dayIdx, int period) {
        if (dayIdx < 1) return 0.01;
        int actualPeriod = Math.min(dayIdx + 1, period);
        double sum = pricePrefixSum[stockIdx][dayIdx];
        double sqSum = priceSqPrefixSum[stockIdx][dayIdx];
        
        if (dayIdx >= actualPeriod) {
            sum -= pricePrefixSum[stockIdx][dayIdx - actualPeriod];
            sqSum -= priceSqPrefixSum[stockIdx][dayIdx - actualPeriod];
        }
        
        double mean = sum / actualPeriod;
        double variance = (sqSum / actualPeriod) - (mean * mean);
        return Math.sqrt(Math.max(0, variance)) / (closePrices[stockIdx][dayIdx] + 0.0001);
    }
}
