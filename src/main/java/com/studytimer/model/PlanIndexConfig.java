package com.studytimer.model;

import java.util.ArrayList;
import java.util.List;

public class PlanIndexConfig {

    private String activePlanId;

    private List<PlanInfo> plans = new ArrayList<>();

    public PlanIndexConfig() {
    }

    public String getActivePlanId() {
        return activePlanId;
    }

    public void setActivePlanId(String activePlanId) {
        this.activePlanId = activePlanId;
    }

    public List<PlanInfo> getPlans() {
        return plans;
    }

    public void setPlans(List<PlanInfo> plans) {
        this.plans = plans;
    }
}
