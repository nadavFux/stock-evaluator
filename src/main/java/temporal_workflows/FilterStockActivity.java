package temporal_workflows;

import common.DTO.BaseStock;

import java.util.List;

public interface FilterStockActivity {
    List<BaseStock> filterStockData(List<BaseStock> stocks);
}
