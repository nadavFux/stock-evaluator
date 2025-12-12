package temporal_workflows;

import common.DTO.Stock;
import common.DTO.StockGraphState;

public interface GraphStockActivity {
    StockGraphState graphStockData(Stock result);
}
