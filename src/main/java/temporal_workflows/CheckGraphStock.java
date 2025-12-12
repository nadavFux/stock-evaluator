package temporal_workflows;

import common.DTO.StockCheckResult;
import common.DTO.StockGraphState;

public interface CheckGraphStock {
    StockCheckResult checkStockData(StockGraphState result);
}
