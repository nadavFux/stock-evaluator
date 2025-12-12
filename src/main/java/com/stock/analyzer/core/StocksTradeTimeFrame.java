package com.stock.analyzer.core;

import com.stock.analyzer.model.dto.StockTrade;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class StocksTradeTimeFrame {
    private final ConcurrentHashMap<String, StockTrade> data = new ConcurrentHashMap<>();
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
        data.compute(trade.getTicker(), (k, existingStock) -> {
            if (existingStock == null) {
                return trade;
            } else {
                double currentTotalValue = 1000 * (1 + existingStock.getLastGained());
                double newTotalValue = currentTotalValue * (1 + trade.getLastGained());
                double updatedTotalGain = (newTotalValue - 1000) / 1000;
                existingStock.UpdateGains(updatedTotalGain, trade.getDays());
                return existingStock;
            }
        });
    }

    public void Merge(StocksTradeTimeFrame other) {
        for (var trade : other.Trades()) {
            AddRow(trade);
        }
    }

    public double getAvg() {
        return data.size() > 0
                ? (data.values().stream().mapToDouble(StockTrade::getLastGained).average().getAsDouble()
                        - 5.9333333333333333333333333333333e-4 * searchTime) / searchTime
                : 0;
    }

    @Override
    public String toString() {
        StringBuilder body = new StringBuilder();
        for (var stock : data.values()) {
            body.append(stock.toString() + "\n");
        }
        return data.size() > 0 ? "from " + startTime + " to " + searchTime + " selected for " + selectTime + "\n\n" +
                body + "\n" + "avgtimeframe," + getAvg() + "\n\n" : "too few data points";
    }

    public Collection<StockTrade> Trades() {
        return data.values();
    }
}
