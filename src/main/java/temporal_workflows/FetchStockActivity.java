package temporal_workflows;

import common.DTO.BaseStock;

import java.io.IOException;
import java.util.List;

public interface FetchStockActivity {
    List<BaseStock> fetchStockData(int sector) throws IOException, InterruptedException;
}
