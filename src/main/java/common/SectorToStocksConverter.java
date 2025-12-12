package common;

import common.DTO.BaseStock;
import common.DTO.PriceEquity;
import common.DTO.Root;
import common.DTO.Score;

import java.util.List;

//TODO: this is ugly, is there a better way?
public class SectorToStocksConverter {
    public static List<BaseStock> Convert(Root root) {
        List<BaseStock> stocks = root.metadata.stream().map(metadata -> {
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
        return stocks;
    }
}
