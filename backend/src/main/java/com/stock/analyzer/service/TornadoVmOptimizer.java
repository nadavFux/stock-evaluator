package com.stock.analyzer.service;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.model.StockGraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;

/**
 * High-Performance GPU Optimizer using TornadoVM 4.0.0.
 * Kernels are flattened and branchless to bypass OpenCL JIT hangs.
 */
public class TornadoVmOptimizer implements Optimizer {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizer.class);
    private static boolean isAvailable = false;

    static {
        try {
            var runtime = uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.getTornadoRuntime();
            if (runtime != null && runtime.getNumBackends() > 0) {
                isAvailable = true;
            }
        } catch (Throwable t) {
            logger.error("TornadoVM initialization failed: {}", t.getMessage());
            t.printStackTrace(); // Print full trace to Colab console
        }
    }

    public static boolean isAvailable() { return isAvailable; }

    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final CpuParamOptimizer fallback;
    
    // Cached buffers to prevent repeated allocation stress
    private DoubleArray iMat;
    private DoubleArray hScores;
    private DoubleArray sMat;
    private IntArray go;

    public TornadoVmOptimizer(SimulationRangeConfig config) {
        this.config = config;
        this.fallback = new CpuParamOptimizer(config);
    }

    @Override public MLModelService getMlService() { return mlService; }

    @Override
    public SimulationParams optimize(List<StockGraphState> allStocks) {
        logger.info("Starting Multi-Start Param Optimization Workflow (GPU)...");
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        int M = 5;
        List<SimulationParams> centers = new ArrayList<>();
        centers.add(centerParamsFromConfig());
        for (int i = 1; i < M; i++) {
            centers.add(fallback.randomize(centers.get(0), 1.0));
        }

        DoubleArray gp = flatten(dataPkg.closePrices, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gps = flatten(dataPkg.pricePrefixSum, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpsq = flatten(dataPkg.priceSqPrefixSum, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gr = flatten(dataPkg.ratings, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gv = flatten(dataPkg.volumes, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gav = flatten(dataPkg.avgVol30d, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray ge = flatten(dataPkg.epss, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray grsi = flatten(dataPkg.rsi, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gc = flatten(dataPkg.caps, dataPkg.stockCount, dataPkg.daysCount);
        IntArray go_full = IntArray.fromArray(dataPkg.offsets);

        List<Double> bestScores = new ArrayList<>(Collections.nCopies(M, -100.0));
        for (int i = 0; i < M; i++) {
            double s = evaluateCandidate(centers.get(i), dataPkg, gp, gps, gpsq, gr, gv, gav, ge, grsi, gc, go_full);
            bestScores.set(i, s);
            logger.info("Center {} Initial Score: {}", i, s);
        }

        double radius = 0.25;
        IntArray allIdx = IntArray.fromArray(java.util.stream.IntStream.range(0, dataPkg.stockCount).toArray());

        for (int gen = 1; gen <= 10 && radius >= 0.05; gen++) {
            logger.info("Generation {} (Radius: {})", gen, radius);
            List<Integer> shuffled = getShuffledIndices(dataPkg.stockCount);
            List<Integer> subset = shuffled.subList(0, Math.max(1, dataPkg.stockCount / 2));
            IntArray gSub = IntArray.fromArray(subset.stream().mapToInt(i -> i).toArray());

            for (int c = 0; c < M; c++) {
                SimulationParams center = centers.get(c);
                double currentBest = bestScores.get(c);

                List<SimulationParams> population = new ArrayList<>();
                for (int i = 0; i < 500; i++) population.add(i == 0 ? center : fallback.randomize(center, radius));

                // Stage 1: Discovery on subset
                List<CandidateResult> disc = evaluateGpu2D(population, gSub, dataPkg, false, gp, gps, gpsq, gr, gv, gav, ge, grsi, gc, go_full);
                List<SimulationParams> elites = disc.stream()
                        .sorted(Comparator.comparingDouble(CandidateResult::score).reversed())
                        .limit(10)
                        .map(CandidateResult::params)
                        .toList();

                // Stage 2: Validation on all stocks
                List<CandidateResult> val = evaluateGpu2D(elites, allIdx, dataPkg, false, gp, gps, gpsq, gr, gv, gav, ge, grsi, gc, go_full);
                CandidateResult best = val.stream().max(Comparator.comparingDouble(CandidateResult::score)).orElse(new CandidateResult(center, currentBest));

                if (best.score() > currentBest) {
                    bestScores.set(c, best.score());
                    centers.set(c, best.params());
                    logger.info("Center {} New Best Score: {}", c, best.score());
                }
            }
            radius *= 0.8;
        }

        int bestIdx = 0;
        for (int i = 1; i < M; i++) if (bestScores.get(i) > bestScores.get(bestIdx)) bestIdx = i;
        logger.info("Selected Center {} as Global Winner with score: {}", bestIdx, bestScores.get(bestIdx));
        return centers.get(bestIdx);
    }

    private double evaluateCandidate(SimulationParams p, SimulationDataPackage pkg, DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav, DoubleArray ge, DoubleArray grsi, DoubleArray gc, IntArray go) {
        IntArray all = IntArray.fromArray(java.util.stream.IntStream.range(0, pkg.stockCount).toArray());
        return evaluateGpu2D(List.of(p), all, pkg, false, gp, gps, gpsq, gr, gv, gav, ge, grsi, gc, go).get(0).score();
    }

    private List<CandidateResult> evaluateGpu2D(List<SimulationParams> candidates, IntArray subset, SimulationDataPackage pkg, boolean rescue,
                                                DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav,
                                                DoubleArray ge, DoubleArray grsi, DoubleArray gc, IntArray go_full) {
        int popSize = candidates.size();
        int numStocks = subset.getSize();
        int days = pkg.daysCount;
        int gridCount = config.startTimes.size() * config.searchTimes.size() * config.selectTimes.size();

        int sMatSize = numStocks * days * 9;
        if (sMat == null || sMat.getSize() < sMatSize) {
            sMat = new DoubleArray(sMatSize);
        }
        
        if (go == null || go.getSize() < numStocks) {
            go = new IntArray(numStocks);
        }

        for (int s = 0; s < numStocks; s++) {
            int sIdx = subset.get(s);
            go.set(s, go_full.get(sIdx));
            for (int d = 0; d < days; d++) {
                int base = s * days + d;
                int fullBase = sIdx * days + d;
                sMat.set(0 * numStocks * days + base, gp.get(fullBase));
                sMat.set(1 * numStocks * days + base, gps.get(fullBase));
                sMat.set(2 * numStocks * days + base, gpsq.get(fullBase));
                sMat.set(3 * numStocks * days + base, gr.get(fullBase));
                sMat.set(4 * numStocks * days + base, gv.get(fullBase));
                sMat.set(5 * numStocks * days + base, gav.get(fullBase));
                sMat.set(6 * numStocks * days + base, ge.get(fullBase));
                sMat.set(7 * numStocks * days + base, grsi.get(fullBase));
                sMat.set(8 * numStocks * days + base, gc.get(fullBase));
            }
        }
        
        DoubleArray pMat = new DoubleArray(popSize * 24);
        for (int i = 0; i < popSize; i++) mapParamsToArray(candidates.get(i), pMat, i * 24);
        
        // Intermediate indicators: 5 fields per stock-day
        int iSize = numStocks * days * 5;
        if (iMat == null || iMat.getSize() < iSize) {
            iMat = new DoubleArray(iSize);
        }
        
        int hSize = popSize * numStocks * days;
        if (hScores == null || hScores.getSize() < hSize) {
            hScores = new DoubleArray(hSize);
        }

        DoubleArray results = new DoubleArray(popSize * numStocks * gridCount * 4);
        
        int[] flatGrid = new int[gridCount * 3];
        int gi = 0;
        for (int s : config.startTimes) for (int sr : config.searchTimes) for (int sl : config.selectTimes) {
            flatGrid[gi++] = s; flatGrid[gi++] = sr; flatGrid[gi++] = sl;
        }
        IntArray gGrid = IntArray.fromArray(flatGrid);

        try {
            TaskGraph tg = new TaskGraph("tg")
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, sMat, go, gGrid, pMat)
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, iMat, hScores)
                .task("t_ind", TornadoVmOptimizer::indicatorKernel, sMat, go, iMat, numStocks, days)
                .task("t_heur", TornadoVmOptimizer::heuristicKernel, sMat, iMat, go, pMat, hScores, numStocks, days, popSize)
                .task("t_sim", TornadoVmOptimizer::simulationKernel, sMat, go, hScores, pMat, gGrid, results, numStocks, days, popSize, gridCount)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, results);
            
            TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot());
            plan.execute();

            List<CandidateResult> out = new ArrayList<>();
            for (int p = 0; p < popSize; p++) {
                double tT = 0; double tD = 0; double sE = 0; double sSqE = 0;
                for (int s = 0; s < numStocks; s++) for (int g = 0; g < gridCount; g++) {
                    int off = (p * numStocks * gridCount + s * gridCount + g) * 4;
                    tT += results.get(off); tD += results.get(off + 1); sE += results.get(off + 2); sSqE += results.get(off + 3);
                }
                
                double score = -100.0;
                if (tT >= 2) {
                    double avgE = sE / tT;
                    double var = (sSqE - (sE * sE / tT)) / (tT - 1.000001);
                    double sd = TornadoMath.sqrt(TornadoMath.max(0.0, var));
                    double sharpe = (avgE / (sd + 0.01)) * TornadoMath.sqrt(252.0);
                    double absF = TornadoMath.min(1.0, tT / 40.0);
                    double denF = TornadoMath.min(1.0, (tT / (numStocks * gridCount)) / 10.0);
                    double volM = TornadoMath.sqrt(absF * denF);
                    double durM = TornadoMath.min(1.0, (tD / (tT + 0.000001)) / 5.0);
                    score = sharpe * volM * durM * 10.0;
                }
                out.add(new CandidateResult(candidates.get(p), score));
            }
            return out;
        } catch (Exception e) {
            logger.error("GPU execution failed, falling back to CPU: {}", e.getMessage());
            return fallback.evaluateParallel(candidates, toList(subset), pkg, rescue);
        }
    }

    /**
     * Stage 1: Pre-calculate technical indicators independent of population weights.
     */
    public static void indicatorKernel(DoubleArray sMat, IntArray offsets, DoubleArray iMat, int numStocks, int days) {
        for (@Parallel int idx = 0; idx < numStocks * days; idx++) {
            int sLoc = idx / days;
            int d = idx % days;
            int offset = offsets.get(sLoc);
            int sOff = sLoc * days + d;
            int iOff = sLoc * days + d;

            // 1. Moving Average (fixed 30d for indicator basis)
            int maT = 30;
            int start = (d - maT + 1 > offset) ? d - maT + 1 : offset;
            int psBaseIdx = 1 * numStocks * days + sLoc * days;
            double psNow = sMat.get(psBaseIdx + d);
            double psPrev = (start > offset) ? sMat.get(psBaseIdx + start - 1) : 0.0;
            double divMa = (double)(d - start + 1);
            double maVal = (psNow - psPrev) / (divMa > 0 ? divMa : 1.0);
            iMat.set(0 * numStocks * days + iOff, maVal);

            // 2. RVol (30d)
            double vol = sMat.get(4 * numStocks * days + sOff);
            double avgVol = sMat.get(5 * numStocks * days + sOff);
            double rvol = (avgVol > 0) ? vol / avgVol : 1.0;
            iMat.set(1 * numStocks * days + iOff, rvol);

            // 3. Momentum (10d)
            int momT = 10;
            int sN = (d - momT + 1 > offset) ? d - momT + 1 : offset;
            double avgN = (sMat.get(psBaseIdx + d) - (sN > offset ? sMat.get(psBaseIdx + sN - 1) : 0.0)) / (double)(d - sN + 1);
            int dP = (d - momT > offset) ? d - momT : offset;
            int sP = (dP - momT + 1 > offset) ? dP - momT + 1 : offset;
            double avgP = (sMat.get(psBaseIdx + dP) - (sP > offset ? sMat.get(psBaseIdx + sP - 1) : 0.0)) / (double)(dP - sP + 1);
            double mom = (avgP > 0) ? avgN / avgP : 1.0;
            iMat.set(2 * numStocks * days + iOff, mom);

            // 4. PEG basis
            double eN = sMat.get(6 * numStocks * days + sOff);
            double eP = (d >= offset + 250) ? sMat.get(6 * numStocks * days + sLoc * days + d - 250) : 0;
            double eG = (eN - eP) / (eP + 0.000001);
            iMat.set(3 * numStocks * days + iOff, eG);

            // 5. Volatility (20d)
            int vT = 20;
            int vS = (d - vT + 1 > offset) ? d - vT + 1 : offset;
            double vCD = (double)(d - vS + 1);
            double vA = (sMat.get(psBaseIdx + d) - (vS > offset ? sMat.get(psBaseIdx + vS - 1) : 0.0)) / (vCD > 0 ? vCD : 1.0);
            double vAS = (sMat.get(2 * numStocks * days + sLoc * days + d) - (vS > offset ? sMat.get(2 * numStocks * days + sLoc * days + vS - 1) : 0.0)) / (vCD > 0 ? vCD : 1.0);
            double varV = vAS - (vA * vA);
            iMat.set(4 * numStocks * days + iOff, TornadoMath.sqrt((varV > 0) ? varV : 0.0));
        }
    }

    /**
     * Stage 2: Heuristic Scorer Kernel using pre-calculated indicators.
     */
    public static void heuristicKernel(DoubleArray sMat, DoubleArray iMat, IntArray offsets, DoubleArray params, DoubleArray hScores, int numStocks, int days, int popSize) {
        for (@Parallel int idx = 0; idx < popSize * numStocks * days; idx++) {
            int pIdx = idx / (numStocks * days); 
            int sLoc = (idx / days) % numStocks; 
            int d = idx % days;
            int pOff = pIdx * 24; 
            int sOff = sLoc * days + d;
            
            int offset = offsets.get(sLoc);
            int lMaT = (int)params.get(pOff + 9);
            double price = sMat.get(0 * numStocks * days + sOff);
            
            // Check validity
            int c_v1 = (d - offset >= lMaT - 1) ? 1 : 0;
            int c_v2 = (price > 0) ? 1 : 0;
            double f_v = (double)(c_v1 * c_v2);
            
            // Market Cap filter
            double cap = sMat.get(8 * numStocks * days + sOff);
            int c_c1 = (cap <= 0) ? 1 : 0;
            int c_c2 = (cap >= params.get(pOff + 8) && cap <= params.get(pOff + 14)) ? 1 : 0;
            double f_c = (double)(c_c1 | c_c2);
            
            // RSI filter
            double f_rsi = (sMat.get(7 * numStocks * days + sOff) <= params.get(pOff + 7)) ? 1.0 : 0.0;

            // Price Momentum filter
            int sL = (int)params.get(pOff + 5);
            double pP = (d >= offset + sL) ? sMat.get(0 * numStocks * days + sLoc * days + d - sL) : 0;
            double f_p = (double)((d < offset + sL) ? 1 : (price >= pP * 0.8 ? 1 : 0));

            // Weights
            double tw = params.get(pOff + 17) + params.get(pOff + 18) + params.get(pOff + 19) + params.get(pOff + 20) + params.get(pOff + 21) + params.get(pOff + 22) + params.get(pOff + 23);
            tw = (tw <= 0) ? 1.0 : tw;

            // 1. MA Gap Score
            double maV = iMat.get(0 * numStocks * days + sOff);
            double maG = price / (maV + 0.000001);
            double maM = (sMat.get(3 * numStocks * days + sOff) > 4.0) ? params.get(pOff + 2) * params.get(pOff + 4) : params.get(pOff + 2);
            double gN = (maG - params.get(pOff + 1)) / (maM - params.get(pOff + 1) + 0.000001);
            double s1 = (1.0 - (gN < 0 ? 0 : (gN > 1 ? 1 : gN))) * (params.get(pOff + 17) / tw);
            
            // 2. Rating Score
            double r = sMat.get(3 * numStocks * days + sOff);
            double rN = (r - params.get(pOff + 12)) / (params.get(pOff + 13) - params.get(pOff + 12) + 0.000001);
            double s2 = (rN < 0 ? 0 : (rN > 1 ? 1 : rN)) * (params.get(pOff + 19) / tw);

            // 3. RVol Score
            double rv = iMat.get(1 * numStocks * days + sOff);
            double rvN = (rv - 0.5) / 1.5;
            double s3 = (rvN < 0 ? 0 : (rvN > 1 ? 1 : rvN)) * (params.get(pOff + 21) / tw);

            // 4. Reversion to Mean
            double dN = (TornadoMath.abs(maG - 1.0)) / 0.20;
            double s4 = (dN < 0 ? 0 : (dN > 1 ? 1 : dN)) * (params.get(pOff + 18) / tw);

            // 5. Momentum Score
            double mom = iMat.get(2 * numStocks * days + sOff);
            double mI = params.get(pOff + 10);
            double mN = (mom - mI) / (mI * 0.3 + 0.000001);
            double s5 = (mN < 0 ? 0 : (mN > 1 ? 1 : mN)) * (params.get(pOff + 20) / tw);

            // 6. PEG Score
            double eG = iMat.get(3 * numStocks * days + sOff);
            double eN = sMat.get(6 * numStocks * days + sOff);
            double pe = price / (eN + 0.000001);
            double peg = (eG > 0 && eN > 0) ? pe / (eG * 100.0) : 2.0;
            double pN = peg / 2.0;
            double s6 = (1.0 - (pN < 0 ? 0 : (pN > 1 ? 1 : pN))) * (params.get(pOff + 22) / tw);

            // 7. Volatility Score
            double v = iMat.get(4 * numStocks * days + sOff);
            double vN = v / 0.05;
            double s7 = (1.0 - (vN < 0 ? 0 : (vN > 1 ? 1 : vN))) * (params.get(pOff + 23) / tw);

            hScores.set(idx, (s1 + s2 + s3 + s4 + s5 + s6 + s7) * f_v * f_c * f_rsi * f_p);
        }
    }

    public static void simulationKernel(DoubleArray sMat, IntArray offsets, DoubleArray hScores, DoubleArray params, IntArray grid, DoubleArray res, int numStocks, int days, int popSize, int gridCount) {
        for (@Parallel int idx = 0; idx < popSize * numStocks * gridCount; idx++) {
            int pIdx = idx / (numStocks * gridCount); 
            int sLoc = (idx / gridCount) % numStocks; 
            int gIdx = idx % gridCount;
            int pOff = pIdx * 24;
            
            int daysBack = grid.get(gIdx * 3);
            int searchTime = grid.get(gIdx * 3 + 1);
            int selectTime = grid.get(gIdx * 3 + 2);
            
            int timeStart = (days - daysBack > 0) ? days - daysBack : 0;
            int searchLimit = (timeStart + searchTime < days) ? timeStart + searchTime : days;
            int absoluteLimit = (selectTime > 0) ? ((timeStart + searchTime + selectTime < days) ? timeStart + searchTime + selectTime : days) : days;
            
            double trades = 0; double totalDays = 0; double sumE = 0; double sumSqE = 0;
            double buyT = params.get(pOff + 16);
            double sellC = params.get(pOff + 0);
            int lMaT = (int)params.get(pOff + 9);
            double rfR = params.get(pOff + 15);
            double dailyRf = rfR / 252.0; 
            int offset = offsets.get(sLoc);

            int state = 0; 
            double buyPrice = 0.0;
            int buyDay = 0;

            int psBaseIdx = 1 * numStocks * days + sLoc * days;

            for (int d = timeStart; d < absoluteLimit; d++) {
                double price = sMat.get(0 * numStocks * days + sLoc * days + d);
                
                int isSearching = (state == 0) ? 1 : 0;
                int isHolding = (state == 1) ? 1 : 0;
                
                double h = hScores.get(pIdx * numStocks * days + sLoc * days + d);
                
                int c_buy_1 = isSearching;
                int c_buy_2 = (d < searchLimit) ? 1 : 0;
                int c_buy_3 = (h > buyT) ? 1 : 0;
                int canBuy = c_buy_1 * c_buy_2 * c_buy_3;
                
                int mS = (d - lMaT + 1 > offset) ? d - lMaT + 1 : offset;
                double psNow = sMat.get(psBaseIdx + d);
                double psPrev = (mS > offset) ? sMat.get(psBaseIdx + mS - 1) : 0.0; 
                double divMa = (double)(d - mS + 1);
                double maVal = (psNow - psPrev) / (divMa > 0 ? divMa : 1.0);

                int c_sell_1 = isHolding;
                int c_sell_2 = (price < maVal * sellC) ? 1 : 0;
                int c_sell_3 = (d == absoluteLimit - 1) ? 1 : 0;
                int canSell = c_sell_1 * (c_sell_2 | c_sell_3);
                
                buyPrice = (canBuy == 1) ? price : buyPrice;
                buyDay = (canBuy == 1) ? d : buyDay;
                
                int tHold = d - buyDay;
                double gain = (price - buyPrice) / (buyPrice + 0.000001);
                double divTHold = (double)tHold;
                double exc = (gain * 100.0 - dailyRf * divTHold * 100.0) / (divTHold > 0 ? divTHold : 1.0);
                
                int c_valid_1 = (canSell == 1) ? 1 : 0;
                int c_valid_2 = (tHold > 0) ? 1 : 0;
                int validTrade = c_valid_1 * c_valid_2;
                
                trades += (double)validTrade;
                totalDays += (double)(validTrade * tHold);
                sumE += (double)validTrade * exc;
                sumSqE += (double)validTrade * (exc * exc);
                
                state = (canBuy == 1) ? 1 : ((canSell == 1) ? 0 : state);
            }
            res.set(idx * 4, trades); res.set(idx * 4 + 1, totalDays); res.set(idx * 4 + 2, sumE); res.set(idx * 4 + 3, sumSqE);
        }
    }

    public SimulationParams randomize(SimulationParams c, double r) {
        return fallback.randomize(c, r);
    }

    private void mapParamsToArray(SimulationParams p, DoubleArray a, int s) {
        a.set(s, p.sellCutOffPerc()); a.set(s + 1, p.lowerPriceToLongAvgBuyIn());
        a.set(s + 2, p.higherPriceToLongAvgBuyIn()); a.set(s + 3, p.timeFrameForUpwardLongAvg());
        a.set(s + 4, p.aboveAvgRatingPricePerc()); a.set(s + 5, p.timeFrameForUpwardShortPrice());
        a.set(s + 6, p.timeFrameForOscillator()); a.set(s + 7, p.maxRSI());
        a.set(s + 8, p.minMarketCap()); a.set(s + 9, p.longMovingAvgTime());
        a.set(s + 10, p.minRateOfAvgInc()); a.set(s + 11, p.maxPERatio());
        a.set(s + 12, p.minRating()); a.set(s + 13, p.maxRating());
        a.set(s + 14, p.maxMarketCap()); a.set(s + 15, p.riskFreeRate());
        a.set(s + 16, p.buyThreshold()); a.set(s + 17, p.movingAvgGapWeight());
        a.set(s + 18, p.reversionToMeanWeight()); a.set(s + 19, p.ratingWeight());
        a.set(s + 20, p.upwardIncRateWeight()); a.set(s + 21, p.rvolWeight());
        a.set(s + 22, p.pegWeight()); a.set(s + 23, p.volatilityCompressionWeight());
    }

    private SimulationParams centerParamsFromConfig() {
        return new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0), config.buyThreshold == null || config.buyThreshold.isEmpty() ? 0.65 : config.buyThreshold.get(0),
                config.movingAvgGapWeight == null || config.movingAvgGapWeight.isEmpty() ? 0.2 : config.movingAvgGapWeight.get(0),
                0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }

    private DoubleArray flatten(double[][] d, int s, int dy) {
        double[] f = new double[s * dy];
        for (int i = 0; i < s; i++) System.arraycopy(d[i], 0, f, i * dy, dy);
        return DoubleArray.fromArray(f);
    }

    private List<Integer> toList(IntArray a) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < a.getSize(); i++) l.add(a.get(i));
        return l;
    }

    private List<Integer> getShuffledIndices(int c) {
        List<Integer> i = new ArrayList<>();
        for (int j = 0; j < c; j++) i.add(j);
        Collections.shuffle(i);
        return i;
    }
}
