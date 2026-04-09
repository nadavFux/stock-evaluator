package com.stock.analyzer.service;

import com.stock.analyzer.model.TrainingSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smile.regression.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.DoubleVector;
import smile.data.Tuple;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MLModelService {
    private static final Logger logger = LoggerFactory.getLogger(MLModelService.class);
    private RandomForest model;
    private final List<TrainingSample> samples = new ArrayList<>();

    public void collectSample(TrainingSample sample) {
        synchronized (samples) {
            samples.add(sample);
        }
    }

    public void train() {
        if (samples.size() < 100) {
            logger.warn("Not enough samples to train ML model (need at least 100, got {})", samples.size());
            return;
        }

        logger.info("Training Random Forest Regressor on {} samples...", samples.size());
        
        double[][] x = new double[samples.size()][7];
        double[] y = new double[samples.size()];

        for (int i = 0; i < samples.size(); i++) {
            x[i] = samples.get(i).getFeatures();
            y[i] = samples.get(i).actualGain();
        }

        String[] names = {"f1", "f2", "f3", "f4", "f5", "f6", "f7"};
        DataFrame df = DataFrame.of(x, names);
        df = df.merge(DoubleVector.of("actualGain", y));

        // Training a Random Forest with 100 trees
        this.model = RandomForest.fit(Formula.lhs("actualGain"), df);
        
        logger.info("ML Model training complete.");
    }

    public double predict(double[] features) {
        if (model == null) return -1.0;
        try {
            return model.predict(Tuple.of(features, model.schema()));
        } catch (Exception e) {
            return -1.0;
        }
    }

    public List<Map<String, Object>> getFeatureImportance() {
        if (model == null) return List.of();
        double[] importance = model.importance();
        String[] names = {"MA Gap", "MA Dist", "Rating", "Momentum", "RVOL", "PEG", "Volatility"};
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < importance.length; i++) {
            result.add(Map.of("name", names[i], "val", importance[i]));
        }
        return result;
    }

    public void saveSamples(String path) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(path))) {
            writer.println("maGap,distFromMA,rating,momentum,rvol,peg,volatility,actualGain");
            for (TrainingSample s : samples) {
                writer.printf("%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                    s.maGap(), s.distFromMA(), s.rating(), s.momentum(), s.rvol(), s.peg(), s.volatility(), s.actualGain());
            }
            logger.info("Training samples saved to: {}", path);
        } catch (IOException e) {
            logger.error("Failed to save samples", e);
        }
    }
}
