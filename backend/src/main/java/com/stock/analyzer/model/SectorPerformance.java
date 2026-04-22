package com.stock.analyzer.model;

import java.io.Serializable;

public record SectorPerformance(
    String sectorName,
    int sectorId,
    double averageReturn,
    double totalMarketCap,
    int stockCount
) implements Serializable {}
