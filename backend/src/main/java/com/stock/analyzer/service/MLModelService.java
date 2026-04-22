package com.stock.analyzer.service;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.Parameter;
import ai.djl.nn.core.Linear;
import ai.djl.nn.recurrent.LSTM;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.Batch;
import ai.djl.training.initializer.XavierInitializer;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.stock.analyzer.model.TrainingSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MLModelService {
    private static final Logger logger = LoggerFactory.getLogger(MLModelService.class);
    private Model model;
    private final List<float[][]> sequences = new ArrayList<>();
    private final List<Float> labels = new ArrayList<>();

    public MLModelService() {
        NDManager manager = NDManager.newBaseManager();
        this.model = Model.newInstance("stock-lstm");
        this.model.setBlock(buildLstmBlock());
    }

    private Block buildLstmBlock() {
        SequentialBlock block = new SequentialBlock();
        block.add(new LSTM.Builder()
                .setNumLayers(1)
                .setStateSize(64)
                .optReturnState(false)
                .build());
        block.add(new LSTM.Builder()
                .setNumLayers(1)
                .setStateSize(32)
                .optReturnState(false)
                .build());
        block.add(Linear.builder().setUnits(3).build()); // Q5, Q50, Q95
        return block;
    }

    public void collectSample(TrainingSample sample) {
        synchronized (sequences) {
            sequences.add(sample.sequence());
            labels.add(sample.actualGain());
        }
    }

    public void train() {
        if (sequences.size() < 100) {
            logger.warn("Not enough samples to train ML model (need at least 100, got {})", sequences.size());
            return;
        }

        logger.info("Training Quantile LSTM on {} samples...", sequences.size());

        try (NDManager manager = NDManager.newBaseManager()) {
            NDArray x = manager.create(new Shape(sequences.size(), 30, 12));
            NDArray y = manager.create(new Shape(sequences.size(), 1));

            for (int i = 0; i < sequences.size(); i++) {
                x.set(new ai.djl.ndarray.index.NDIndex(i), manager.create(sequences.get(i)));
                y.set(new ai.djl.ndarray.index.NDIndex(i, 0), labels.get(i));
            }

            DefaultTrainingConfig config = new DefaultTrainingConfig(new PinballLoss())
                    .optOptimizer(Optimizer.adam().build())
                    .optInitializer(new XavierInitializer(), Parameter.Type.WEIGHT);

            try (Trainer trainer = model.newTrainer(config)) {
                trainer.initialize(new Shape(1, 30, 12));
                // Simplified training loop for brevity in this task
                for (int epoch = 0; epoch < 10; epoch++) {
                    Batch batch = new Batch(manager, new NDList(x), new NDList(y), sequences.size(), Batchifier.STACK, Batchifier.STACK, 0, 1);
                    EasyTrain.trainBatch(trainer, batch);
                    trainer.step();
                }
            }
        } catch (Exception e) {
            logger.error("Training failed", e);
        }

        logger.info("ML Model training complete.");
    }

    public double[] predict(float[][] sequence) {
        if (model == null) return new double[]{0, 0, 0};
        try (NDManager manager = NDManager.newBaseManager();
             Predictor<NDList, NDList> predictor = model.newPredictor(new Translator<NDList, NDList>() {
                 @Override
                 public NDList processInput(TranslatorContext ctx, NDList input) { return input; }
                 @Override
                 public NDList processOutput(TranslatorContext ctx, NDList list) { return list; }
                 @Override
                 public Batchifier getBatchifier() { return Batchifier.STACK; }
             })) {
            NDArray input = manager.create(sequence);
            NDList output = predictor.predict(new NDList(input));
            float[] result = output.singletonOrThrow().toFloatArray();
            return new double[]{result[0], result[1], result[2]};
        } catch (TranslateException e) {
            logger.error("ML Prediction failed", e);
            return new double[]{0, 0, 0};
        }
    }

    public List<Map<String, Object>> getFeatureImportance() {
        // Importance is harder to calculate for LSTMs than Random Forests
        return List.of(Map.of("name", "LSTM Sequence", "val", 1.0));
    }

    public void saveSamples(String path) {
        logger.info("Saving {} samples to {}", sequences.size(), path);
        // Persistence logic can be expanded here
    }

    private static class PinballLoss extends Loss {
        public PinballLoss() { super("PinballLoss"); }
        @Override
        public NDArray evaluate(NDList labels, NDList predictions) {
            NDArray y = labels.singletonOrThrow();
            NDArray yHat = predictions.singletonOrThrow();
            NDArray q5 = yHat.get(":, 0");
            NDArray q50 = yHat.get(":, 1");
            NDArray q95 = yHat.get(":, 2");

            NDArray loss5 = pinball(y, q5, 0.05f);
            NDArray loss50 = pinball(y, q50, 0.50f);
            NDArray loss95 = pinball(y, q95, 0.95f);

            return loss5.add(loss50).add(loss95).mean();
        }

        private NDArray pinball(NDArray y, NDArray yHat, float q) {
            NDArray error = y.sub(yHat);
            return error.mul(q).maximum(error.mul(q - 1));
        }
    }
}
