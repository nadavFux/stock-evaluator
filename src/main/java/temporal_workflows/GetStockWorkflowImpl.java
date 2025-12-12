package temporal_workflows;

import common.DTO.ExecutionResult;
import project.StockProcessor;

public class GetStockWorkflowImpl implements GetStockWorkflow {
    private final StockProcessor processor;

    public GetStockWorkflowImpl(FetchStockActivity fetchStockActivity,
                                FilterStockActivity filterStockActivity,
                                EnrichStockActivity enrichStockActivity,
                                GraphStockActivity graphStockActivity,
                                CheckGraphStock checkGraphStock) {
        this.processor = new StockProcessor(
                fetchStockActivity,
                filterStockActivity,
                enrichStockActivity,
                graphStockActivity,
                checkGraphStock
        );
    }

    @Override
    public ExecutionResult getStockData(int sector) {
        return processor.getStockData(sector);
    }
}
