package com.studytimer.service;

import com.studytimer.model.PlanInfo;
import com.studytimer.model.TimerStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanStorageServiceTest {

    @TempDir
    Path dir;

    private PlanStorageService service;

    private static List<TimerStage> stages() {
        return List.of(new TimerStage("专注", 25), new TimerStage("休息", 5));
    }

    @BeforeEach
    void setUp() throws IOException {
        service = new PlanStorageService(dir);
        service.initialize(stages());
    }

    @Test
    void initializeCreatesActivePlanWithDefaults() throws IOException {
        assertFalse(service.getPlans().isEmpty());
        List<TimerStage> loaded = service.loadPlan(service.getActivePlanId());
        assertEquals(2, loaded.size());
        assertEquals("专注", loaded.get(0).getName());
        assertEquals(25, loaded.get(0).getMinutes());
    }

    @Test
    void saveAndReloadInNewInstance() throws IOException {
        String id = service.getActivePlanId();
        TimerStage s = new TimerStage("阅读", 40);
        s.setAudioLeadSeconds(10);
        s.setPhoneVolume(40);
        service.savePlan(id, List.of(s));

        PlanStorageService reopened = new PlanStorageService(dir);
        reopened.initialize(stages());
        List<TimerStage> loaded = reopened.loadPlan(id);
        assertEquals(1, loaded.size());
        assertEquals("阅读", loaded.get(0).getName());
        assertEquals(40, loaded.get(0).getMinutes());
        assertEquals(10, loaded.get(0).getAudioLeadSeconds());
        assertEquals(40, loaded.get(0).getPhoneVolume());
    }

    @Test
    void oldPlanWithoutVolumeDefaultsToFull() throws IOException {
        Path file = dir.resolve("old.json");
        Files.writeString(file, "[{\"id\":\"a\",\"name\":\"旧\",\"minutes\":5,"
                + "\"audioFile\":\"\",\"audioLeadSeconds\":30,\"phoneAudioId\":null}]");
        PlanInfo info = service.importPlan(file, "旧计划");
        assertEquals(100, service.loadPlan(info.getId()).get(0).getPhoneVolume());
    }

    @Test
    void initializeDoesNotOverwriteExistingPlans() throws IOException {
        String id = service.getActivePlanId();
        service.savePlan(id, List.of(new TimerStage("自定义", 50)));

        PlanStorageService reopened = new PlanStorageService(dir);
        reopened.initialize(stages());
        assertEquals("自定义", reopened.loadPlan(id).get(0).getName());
    }

    @Test
    void overwriteCreatesBackup() throws IOException {
        String id = service.getActivePlanId();
        service.savePlan(id, List.of(new TimerStage("v2", 30)));
        service.savePlan(id, List.of(new TimerStage("v3", 35)));
        assertFalse(service.listPlanBackups(id).isEmpty());
    }

    @Test
    void corruptPlanFileRecoversFromBackup() throws IOException {
        String id = service.getActivePlanId();
        service.savePlan(id, List.of(new TimerStage("v2", 30)));
        service.savePlan(id, List.of(new TimerStage("v3", 35)));

        Files.writeString(service.getPlanFile(id), "{ broken");

        List<TimerStage> loaded = service.loadPlan(id);
        assertFalse(loaded.isEmpty(), "损坏的计划应从备份恢复");
    }

    @Test
    void createDuplicateRenameDelete() throws IOException {
        PlanInfo created = service.createPlan("周末", stages());
        PlanInfo copy = service.duplicatePlan(created.getId(), "周末副本");
        assertNotEquals(created.getId(), copy.getId());

        service.renamePlan(created.getId(), "周末计划");
        assertTrue(service.getPlans().stream()
                .anyMatch(p -> p.getName().equals("周末计划")));

        service.deletePlan(copy.getId());
        assertTrue(service.getPlans().stream()
                .noneMatch(p -> p.getId().equals(copy.getId())));
    }

    @Test
    void movePlanChangesOrder() throws IOException {
        PlanInfo second = service.createPlan("第二", stages());
        String firstId = service.getPlans().get(0).getId();

        service.movePlan(second.getId(), -1);
        assertEquals(second.getId(), service.getPlans().get(0).getId());

        service.movePlan(second.getId(), 1);
        assertEquals(firstId, service.getPlans().get(0).getId());
    }

    @Test
    void exportImportCreatesNewId() throws IOException {
        Path export = dir.resolve("export.json");
        service.exportPlan(service.getActivePlanId(), export);

        PlanInfo a = service.importPlan(export, "导入A");
        PlanInfo b = service.importPlan(export, "导入B");
        assertNotEquals(a.getId(), b.getId());
        assertEquals(2, service.loadPlan(a.getId()).size());
    }

    @Test
    void importRejectsEmptyAndInvalid() throws IOException {
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "[]");
        assertThrows(IOException.class, () -> service.importPlan(empty, "空"));

        Path invalid = dir.resolve("invalid.json");
        Files.writeString(invalid, "{ invalid json");
        assertThrows(IOException.class, () -> service.importPlan(invalid, "坏"));
    }
}
