package com.stock.analyzer.service;

import com.stock.analyzer.model.BaseStock;
import com.stock.analyzer.model.PriceEquity;
import com.stock.analyzer.model.Root;
import com.stock.analyzer.model.Score;

import java.util.List;

public class DataConverter {
    public static List<BaseStock> convert(Root root) {
        return root.metadata.stream().map(metadata -> {
            String companyId = metadata.company_id;

            PriceEquity priceEquity = root.price_equity.stream()
                    .filter(pe -> pe.company_id.equals(companyId))
                    .findFirst()
                    .orElse(new PriceEquity());

            Score score = root.score.stream()
                    .filter(sc -> sc.company_id.equals(companyId))
                    .findFirst()
                    .orElse(new Score());

            return new BaseStock(
                    metadata.company_id,
                    metadata.name,
                    metadata.ticker_symbol,
                    metadata.exchange_symbol,
                    priceEquity.filing_date,
                    priceEquity.market_cap_before_filing_date,
                    score.final_assessment,
                    score.buying_recommendation
            );
        }).toList();
    }
}
