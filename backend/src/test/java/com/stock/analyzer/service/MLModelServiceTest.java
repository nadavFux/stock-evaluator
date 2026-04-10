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
            service.collectSample(new TrainingSample(1.0 + i*0.01, 0.1, 5.0, 1.0, 1.0, 1.0, 0.02, 0.05 + i*0.001));
        }
        
        service.train();
        
        double[] features = new double[]{1.05, 0.1, 5.0, 1.0, 1.0, 1.0, 0.02};
        double prediction = service.predict(features);
        
        // Prediction should be around 0.05 + 5*0.001 = 0.055
        // Since it's a random forest with many samples, it should at least not return -1.0 (error)
        assertNotEquals(-1.0, prediction, "Prediction should not fail");
        assertTrue(prediction > 0.04 && prediction < 0.2, "Prediction should be reasonable");
    }
}
