package com.stock.analyzer.core;

import com.stock.analyzer.model.StockTrade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class StocksTradeTimeFrame {
    private final List<StockTrade> data = new ArrayList<>();
    public final int startTime;
    public final int searchTime;
    public final int selectTime;
    public final String key;


    public StocksTradeTimeFrame(int startTime, int searchTime, int selectTime) {
        this.startTime = startTime;
        this.searchTime = searchTime;
        this.selectTime = selectTime;
        this.key = GenerateKey(startTime, searchTime, selectTime);
    }

    public static String GenerateKey(int startTime, int searchTime, int selectTime) {
        return startTime + "," + searchTime + "," + selectTime;
    }

    public void AddRow(StockTrade trade) {
        data.add(trade);
    }

    public double getAvg() {
        return data.size() > 0 ? (data.stream().mapToDouble(StockTrade::getLastGained).average().getAsDouble() - 5.9333333333333333333333333333333e-4 * searchTime) / searchTime : 0;
    }

    @Override
    public String toString() {
        StringBuilder body = new StringBuilder();
        for (var stock : data) {
            body.append(stock.toString() + "\n");
        }
        return data.size() > 0 ? "from " + startTime + " to " + searchTime + " selected for " + selectTime + "\n\n" +
                body + "\n" + "avgtimeframe," + getAvg() + "\n\n" :
                "too few data points";
    }

    public Collection<StockTrade> Trades() {
        return data;
    }
}
