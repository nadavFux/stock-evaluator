package temporal_workflows;

import common.DTO.BaseStock;

import java.util.List;

public class FilterNonStockActivityImpl implements FilterStockActivity {
    @Override
    public List<BaseStock> filterStockData(List<BaseStock> stocks) {
        return stocks;
    }
}
