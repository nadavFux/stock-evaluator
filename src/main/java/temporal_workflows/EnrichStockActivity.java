package temporal_workflows;

import common.DTO.BaseStock;
import common.DTO.Stock;

public interface EnrichStockActivity {
    Stock enrichStockData(BaseStock result);
}
