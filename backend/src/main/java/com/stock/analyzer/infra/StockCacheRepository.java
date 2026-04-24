package com.stock.analyzer.infra;

import com.stock.analyzer.model.StockCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockCacheRepository extends JpaRepository<StockCacheEntity, String> {
}
