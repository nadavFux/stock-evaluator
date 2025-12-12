package common;

import common.DTO.StockTrade;

import java.util.Collection;
import java.util.HashMap;

public class StocksTradeTimeFrame {
    private final HashMap<String, StockTrade> data = new HashMap<>();
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
        if (!data.containsKey(trade.getTicker())) {
            data.put(trade.getTicker(), trade);
        } else {
            var stock = data.get(trade.getTicker());
            double currentTotalValue = 1000 * (1 + stock.getLastGained());
            double newTotalValue = currentTotalValue * (1 + trade.getLastGained());
            double updatedTotalGain = (newTotalValue - 1000) / 1000;
            stock.UpdateGains(updatedTotalGain, trade.getDays());
        }
    }

    public double getAvg() {
        return data.size() > 0 ? (data.values().stream().mapToDouble(StockTrade::getLastGained).average().getAsDouble() - 5.9333333333333333333333333333333e-4 * searchTime) / searchTime : 0;
    }

    @Override
    public String toString() {
        StringBuilder body = new StringBuilder();
        for (var stock : data.values()) {
            body.append(stock.toString() + "\n");
        }
        return data.size() > 0 ? "from " + startTime + " to " + searchTime + " selected for " + selectTime + "\n\n" +
                body + "\n" + "avgtimeframe," + getAvg() + "\n\n" :
                "too few data points";
    }

    public Collection<StockTrade> Trades() {
        return data.values();
    }
}
