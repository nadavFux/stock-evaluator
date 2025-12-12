package temporal_workflows;

import common.DTO.ExecutionResult;

public interface GetStockWorkflow {
    ExecutionResult getStockData(int sector);
}
