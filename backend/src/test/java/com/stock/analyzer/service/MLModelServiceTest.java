package com.stock.analyzer.service;

import com.stock.analyzer.model.TrainingSample;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class MLModelServiceTest {

    @Test
    public void testTrainAndPredict() {
        MLModelService service = new MLModelService();
        
        // Add 100 samples to enable training
        for (int i = 0; i < 100; i++) {
            float[][] sequence = new float[30][12];
            for (int k = 0; k < 30; k++) {
                for (int f = 0; f < 12; f++) sequence[k][f] = (float) (0.5 + i * 0.001);
            }
            service.collectSample(new TrainingSample(sequence, (float) (0.05 + i * 0.001)));
        }
        
        service.train();
        
        float[][] testSequence = new float[30][12];
        for (int k = 0; k < 30; k++) {
            for (int f = 0; f < 12; f++) testSequence[k][f] = 0.55f;
        }
        double[] prediction = service.predict(testSequence);
        
        assertNotNull(prediction);
        assertEquals(3, prediction.length);
        // Median (Q50) should be around 0.1
        assertTrue(prediction[1] > 0.0, "Prediction should be positive");
    }
}
