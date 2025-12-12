package project;

import java.util.List;

public class Main2 {
    public static double calculateSimulationScore(List<Trade> trades) {
        double totalExcessReturn = 0.0;
        int tradeCount = 0;
        double dailyRiskFreeRate = Math.pow(1 + 0.1, 1.0 / 250) - 1;
        for (var trade : trades) {
            double tradeReturn = trade.getReturnPercentage();
            double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDaysHeld()) - 1) * 100;
            totalExcessReturn += (tradeReturn - riskFreeReturn);
            tradeCount++;
        }
        return totalExcessReturn / tradeCount;
    }

    /**
     * Calculates the excess return of a trading strategy compared to a risk-free rate
     *
     * @param trades             List of Trade objects containing days held and return percentages
     * @param annualRiskFreeRate The annual risk-free rate (e.g., 0.03 for 3%)
     * @return The total excess return as a percentage
     */
    public static double calculateExcessReturn(List<Trade> trades, double annualRiskFreeRate) {
        double totalStrategyReturn = 0.0;
        double totalRiskFreeReturn = 0.0;

        // Convert annual risk-free rate to daily rate
        double dailyRiskFreeRate = Math.pow(1 + annualRiskFreeRate, 1.0 / 250) - 1;

        for (Trade trade : trades) {
            // Add the strategy return for this trade
            totalStrategyReturn += trade.getReturnPercentage();

            // Calculate the risk-free return for the same holding period
            double riskFreeReturnForPeriod = (Math.pow(1 + dailyRiskFreeRate, trade.getDaysHeld()) - 1) * 100;
            totalRiskFreeReturn += riskFreeReturnForPeriod;
        }

        return totalStrategyReturn - totalRiskFreeReturn;
    }

    /**
     * Calculates the annualized excess return of a trading strategy
     *
     * @param trades             List of Trade objects
     * @param annualRiskFreeRate The annual risk-free rate
     * @param totalDaysInPeriod  Total days the strategy was active (for annualization)
     * @return The annualized excess return as a percentage
     */
    public static double calculateAnnualizedExcessReturn(List<Trade> trades, double annualRiskFreeRate, int totalDaysInPeriod) {
        double totalExcessReturn = calculateExcessReturn(trades, annualRiskFreeRate);

        // Convert to decimal for calculation
        double excessReturnDecimal = totalExcessReturn / 100.0;

        // Annualize the return
        double annualizedReturn = Math.pow(1 + excessReturnDecimal, 250.0 / totalDaysInPeriod) - 1;

        return annualizedReturn * 100; // Convert back to percentage
    }

    /**
     * Alternative method that calculates excess return by comparing compound returns
     * This method compounds all trades and then subtracts the risk-free return
     *
     * @param trades             List of Trade objects
     * @param annualRiskFreeRate The annual risk-free rate
     * @return The excess return as a percentage
     */
    public static double calculateCompoundExcessReturn(List<Trade> trades, double annualRiskFreeRate) {
        double strategyCompoundReturn = 1.0;
        double riskFreeCompoundReturn = 1.0;

        // Convert annual risk-free rate to daily rate
        double dailyRiskFreeRate = Math.pow(1 + annualRiskFreeRate, 1.0 / 250.0) - 1;

        for (Trade trade : trades) {
            // Compound the strategy returns
            strategyCompoundReturn *= (1 + trade.getReturnPercentage() / 100.0);

            // Compound the risk-free returns for the same period
            riskFreeCompoundReturn *= Math.pow(1 + dailyRiskFreeRate, trade.getDaysHeld());
        }

        // Return the difference in percentage points
        return (strategyCompoundReturn - riskFreeCompoundReturn) * 100;
    }

    /**
     * Trade class to represent individual trades
     */
    public static class Trade {
        private final int daysHeld;
        private final double returnPercentage;

        public Trade(int daysHeld, double returnPercentage) {
            this.daysHeld = daysHeld;
            this.returnPercentage = returnPercentage;
        }

        public int getDaysHeld() {
            return daysHeld;
        }

        public double getReturnPercentage() {
            return returnPercentage;
        }

        @Override
        public String toString() {
            return String.format("Trade{days=%d, return=%.2f%%}", daysHeld, returnPercentage);
        }
    }

    // Example usage
    public static void main(String[] args) {
        // Create sample trades
        List<Trade> trades = List.of(
                new Trade(250, 12)    // 5 days, 2.5% return
        );

        double riskFreeRate = 0.1; // 3% annual risk-free rate

        // Calculate different metrics
        double x = calculateSimulationScore(trades);
        double excessReturn = calculateExcessReturn(trades, riskFreeRate);
        double annualizedExcessReturn = calculateAnnualizedExcessReturn(trades, riskFreeRate, 250);
        double compoundExcessReturn = calculateCompoundExcessReturn(trades, riskFreeRate);

        System.out.printf("%.2f%%\n", x);
        System.out.printf("Total Excess Return: %.2f%%\n", excessReturn);
        System.out.printf("Annualized Excess Return: %.2f%%\n", annualizedExcessReturn);
        System.out.printf("Compound Excess Return: %.2f%%\n", compoundExcessReturn);
    }
}