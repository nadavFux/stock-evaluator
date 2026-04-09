package com.stock.analyzer.model;

import java.io.Serializable;
import java.util.List;

public record StockGraphState(Stock stock,
                              List<Double> rating,
                              double low,
                              double high,
                              double price,
                              List<Double> closePrices,
                              List<Double> volumes,
                              List<Double> avgs,
                              List<String> dates,
                              List<Double> epss,
                              List<Double> caps)
        implements Serializable {

}

