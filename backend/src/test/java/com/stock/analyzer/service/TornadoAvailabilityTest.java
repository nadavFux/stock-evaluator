package com.stock.analyzer.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TornadoAvailabilityTest {
    @Test
    public void testAvailability() {
        System.out.println("Checking TornadoVM availability...");
        boolean available = TornadoVmOptimizer.isAvailable();
        System.out.println("TornadoVM available: " + available);
        
        if (available) {
            var runtime = uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.getTornadoRuntime();
            for (int i = 0; i < runtime.getNumBackends(); i++) {
                var backend = runtime.getBackend(i);
                System.out.println("Backend " + i + ": " + backend.getName());
                for (int j = 0; j < backend.getNumDevices(); j++) {
                    System.out.println("  Device " + j + ": " + backend.getDevice(j).getDeviceName());
                }
            }
        }
    }
}
