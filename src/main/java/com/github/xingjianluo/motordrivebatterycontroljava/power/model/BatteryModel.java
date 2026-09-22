package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 进程内电池模型：根据荷电状态与最大放电电流给出可用放电功率上限。
 * <ul>
 *   <li>电流约束功率：P_i = U_nom * I_max</li>
 *   <li>SOC 约束功率：低电量段（SOC &lt; {@link #LOW_SOC}）功率线性收敛到 0，
 *       其余区间等于电流约束功率。</li>
 * </ul>
 * 模型读数仅在结算（实际能量扣减）时变化，因此在一次预留生命周期内预算可被"冻结"。
 */
public class BatteryModel {

    /** 低于该 SOC 后可用功率随 SOC 线性下降 */
    public static final double LOW_SOC = 0.20;

    private final double nominalVoltage;
    private final double capacityAh;
    private final double maxDischargeCurrentA;
    private double soc; // 0..1

    public BatteryModel(double nominalVoltage, double capacityAh, double maxDischargeCurrentA, double initialSoc) {
        if (nominalVoltage <= 0 || capacityAh <= 0 || maxDischargeCurrentA <= 0) {
            throw new IllegalArgumentException("电池参数必须为正数");
        }
        if (initialSoc < 0 || initialSoc > 1) {
            throw new IllegalArgumentException("初始 SOC 必须位于 [0,1]");
        }
        this.nominalVoltage = nominalVoltage;
        this.capacityAh = capacityAh;
        this.maxDischargeCurrentA = maxDischargeCurrentA;
        this.soc = initialSoc;
    }

    /** 剩余能量（Wh）= U_nom * 容量Ah * SOC */
    public double remainingEnergyWh() {
        return nominalVoltage * capacityAh * soc;
    }

    /** 按当前 SOC 的可用放电功率上限（W） */
    public double socPowerLimitW() {
        double socFactor = soc <= 0.0 ? 0.0
                : soc >= LOW_SOC ? 1.0
                : soc / LOW_SOC;
        return currentPowerLimitW() * socFactor;
    }

    /** 最大放电电流折算功率上限（W）：P = U * I */
    public double currentPowerLimitW() {
        return nominalVoltage * maxDischargeCurrentA;
    }

    /** 结算时按实际消耗能量扣减，SOC 单调下降，不低于 0 */
    public void consumeEnergyWh(double energyWh) {
        if (energyWh < 0) {
            throw new IllegalArgumentException("消耗能量不能为负");
        }
        double totalWh = nominalVoltage * capacityAh;
        soc = Math.max(0.0, soc - energyWh / totalWh);
    }

    public double getSoc() {
        return soc;
    }

    void setSocForReplay(double soc) {
        this.soc = Math.min(1.0, Math.max(0.0, soc));
    }

    public double getNominalVoltage() {
        return nominalVoltage;
    }

    public double getCapacityAh() {
        return capacityAh;
    }

    public double getMaxDischargeCurrentA() {
        return maxDischargeCurrentA;
    }
}
