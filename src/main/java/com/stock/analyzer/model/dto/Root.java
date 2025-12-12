package com.stock.analyzer.model.dto;

import java.util.List;

//TODO: is there a better way to implement these middle classes?
public class Root {
    public List<Metadata> metadata;
    public List<PriceEquity> price_equity;
    public List<Score> score;
}


