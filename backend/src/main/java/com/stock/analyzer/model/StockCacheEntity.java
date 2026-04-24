package com.stock.analyzer.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "STOCK_CACHE")
public class StockCacheEntity {
    @Id
    private String ticker;
    private LocalDate lastUpdated;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String graphDataJson;

    public StockCacheEntity() {}
    public StockCacheEntity(String ticker, LocalDate lastUpdated, String graphDataJson) {
        this.ticker = ticker;
        this.lastUpdated = lastUpdated;
        this.graphDataJson = graphDataJson;
    }
    public String getTicker() { return ticker; }
    public LocalDate getLastUpdated() { return lastUpdated; }
    public String getGraphDataJson() { return graphDataJson; }
}
