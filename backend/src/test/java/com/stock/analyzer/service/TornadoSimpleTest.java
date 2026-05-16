package com.stock.analyzer.service;

import org.junit.jupiter.api.Test;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TornadoSimpleTest {
    public static void simpleKernel(FloatArray a, FloatArray b, FloatArray c) {
        for (@Parallel int i = 0; i < c.getSize(); i++) {
            c.set(i, a.get(i) + b.get(i));
        }
    }

    @Test
    public void testSimpleAddition() {
        if (!TornadoVmOptimizer.isAvailable()) return;

        int n = 1024;
        FloatArray a = new FloatArray(n);
        FloatArray b = new FloatArray(n);
        FloatArray c = new FloatArray(n);

        for (int i = 0; i < n; i++) {
            a.set(i, (float) i);
            b.set(i, (float) i);
        }

        TaskGraph graph = new TaskGraph("s0")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                .task("t0", TornadoSimpleTest::simpleKernel, a, b, c)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, c);

        TornadoExecutionPlan plan = new TornadoExecutionPlan(graph.snapshot());
        plan.execute();

        for (int i = 0; i < n; i++) {
            assertEquals((float) (i + i), c.get(i), 0.01f);
        }
        System.out.println("Simple TornadoVM test passed!");
    }
}
