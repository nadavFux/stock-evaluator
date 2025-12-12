package common.DTO;

public class SimulationResult {
    public double eval;
    public double lastGain;

    public SimulationResult(double eval, double lastGain) {
        this.eval = eval;
        this.lastGain = lastGain;
    }

    public double getEval() {
        return eval;
    }

}
