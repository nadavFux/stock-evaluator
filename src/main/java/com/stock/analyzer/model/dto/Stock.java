package com.stock.analyzer.model.dto;

import java.io.Serializable;

public record Stock(String company_id, String name, String ticker_symbol, String exchange_symbol, String filing_date,
                    float market_cap_before_filing_date, double final_assessment, double buying_recommendation,
                    String identifier,
                    String otherName,
                    Double tech_assessment) implements Serializable {
}

