package common.DTO;

public class StockTrade {
    public String getTicker() {
        return ticker;
    }

    private final String ticker;
    private Double lastGained;
    private final Integer startDaysAgo;
    private Integer days;
    private final Double startingPriceOverAvg;
    private final Double cutoff;
    private final String date;
    private int trades;
    public String eval;


    public StockTrade(String ticker, Double lastGained, Integer startDaysAgo, Integer days, Double startingPriceOverAvg, Double cutoff, String date) {
        this.ticker = ticker;
        this.lastGained = lastGained;
        this.startDaysAgo = startDaysAgo;
        this.days = days;
        this.startingPriceOverAvg = startingPriceOverAvg;
        this.cutoff = cutoff;
        this.date = date;
        this.trades = 0;
    }

    public StockTrade(String ticker, Double lastGained, Integer startDaysAgo, Integer days, Double startingPriceOverAvg, Double cutoff, String date, String eval) {
        this(ticker, lastGained, startDaysAgo, days, startingPriceOverAvg, cutoff, date);
        this.eval = eval;
    }

    public void UpdateGains(double gain, int days) {
        this.lastGained = gain;
        this.trades += 2;
        this.days += days;
    }

    public Double getLastGained() {
        return lastGained;
    }

    public int getDays() {
        return days;
    }

    @Override
    public String toString() {
        return ticker + "," + lastGained + "," + startDaysAgo + "," + days + "," + startingPriceOverAvg + "," + cutoff + "," + date + "," + trades;
    }
}
