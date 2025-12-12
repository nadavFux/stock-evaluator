package common.DTO;

import java.io.Serializable;

public record StockCheckResult(StockGraphState stock, SimulationResult result) implements Serializable {
}