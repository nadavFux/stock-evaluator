package com.stock.analyzer.model;

import java.io.Serializable;

public record TradePoint(String date, double price, String type) implements Serializable {
}
