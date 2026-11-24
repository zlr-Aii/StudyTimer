package com.studytimer.service;

import com.studytimer.model.PlanInfo;
import com.studytimer.model.TimerStage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates Focus plan operations independently of the JavaFX interface.
 *
 * The controller owns the plan-storage service and exposes application-level
 * operations such as switching, creating, duplicating and deleting the active
 * plan. UI dialogs and JavaFX controls remain in Main.
 */
public final class FocusPlanController {

    private final PlanStorageService storage;

    public FocusPlanController() {
        this(new PlanStorageService());
    }

    public FocusPlanController(PlanStorageService storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public void initialize(List<TimerStage> defaultStages) throws IOException {
        storage.initialize(defaultStages);
    }

    public List<PlanInfo> getPlans() {
        return storage.getPlans();
    }

    public String getActivePlanId() {
        return storage.getActivePlanId();
    }

    public PlanInfo getActivePlanInfo() {
        String activeId = getActivePlanId();
        if (activeId == null) {
            return null;
        }
        return storage.getPlans().stream()
                .filter(plan -> activeId.equals(plan.getId()))
                .findFirst()
                .orElse(null);
    }

    public List<TimerStage> loadActivePlan() throws IOException {
        return storage.loadPlan(requireActivePlanId());
    }

    public List<TimerStage> loadPlan(String planId) throws IOException {
        return storage.loadPlan(planId);
    }

    public void saveActivePlan(List<TimerStage> stages) throws IOException {
        storage.savePlan(requireActivePlanId(), stages);
    }

    /** Loads the target before changing the active-plan pointer. */
    public List<TimerStage> switchActivePlan(String targetPlanId)
            throws IOException {
        List<TimerStage> targetStages = storage.loadPlan(targetPlanId);
        storage.setActivePlan(targetPlanId);
        return targetStages;
    }

    /** Creates a plan and makes it active. */
    public ActivePlan createAndActivate(
            String name,
            List<TimerStage> initialStages) throws IOException {
        PlanInfo plan = storage.createPlan(name, initialStages);
        storage.setActivePlan(plan.getId());
        return new ActivePlan(plan, storage.loadPlan(plan.getId()));
    }

    /** Duplicates the active plan and makes the copy active. */
    public ActivePlan duplicateActiveAndActivate(String newName)
            throws IOException {
        String activeId = requireActivePlanId();
        PlanInfo copy = storage.duplicatePlan(activeId, newName);
        storage.setActivePlan(copy.getId());
        return new ActivePlan(copy, storage.loadPlan(copy.getId()));
    }

    public void renameActivePlan(String newName) throws IOException {
        storage.renamePlan(requireActivePlanId(), newName);
    }

    public void renamePlan(String planId, String newName) throws IOException {
        storage.renamePlan(planId, newName);
    }

    public void updatePlanDescription(String planId, String description)
            throws IOException {
        storage.updatePlanDescription(planId, description);
    }

    public void updatePlanCategory(String planId, String category)
            throws IOException {
        storage.updatePlanCategory(planId, category);
    }

    /** Deletes the active plan and loads whichever plan becomes active. */
    public ActivePlan deleteActivePlan() throws IOException {
        storage.deletePlan(requireActivePlanId());
        String newActiveId = requireActivePlanId();
        PlanInfo newActivePlan = getActivePlanInfo();
        return new ActivePlan(newActivePlan, storage.loadPlan(newActiveId));
    }

    public void movePlan(String planId, int direction) throws IOException {
        storage.movePlan(planId, direction);
    }

    public List<Path> listPlanBackups(String planId) throws IOException {
        return storage.listPlanBackups(planId);
    }

    public List<TimerStage> previewPlanBackup(String planId, Path backupFile)
            throws IOException {
        return storage.previewPlanBackup(planId, backupFile);
    }

    public void restorePlanBackup(String planId, Path backupFile)
            throws IOException {
        storage.restorePlanBackup(planId, backupFile);
    }

    public void exportPlan(String planId, Path targetFile) throws IOException {
        storage.exportPlan(planId, targetFile);
    }

    public PlanInfo importPlan(Path sourceFile, String newName)
            throws IOException {
        return storage.importPlan(sourceFile, newName);
    }

    private String requireActivePlanId() {
        String activeId = storage.getActivePlanId();
        if (activeId == null || activeId.isBlank()) {
            throw new IllegalStateException("当前没有激活的计划。");
        }
        return activeId;
    }

    public record ActivePlan(PlanInfo info, List<TimerStage> stages) {
        public ActivePlan {
            stages = List.copyOf(stages);
        }
    }
}
