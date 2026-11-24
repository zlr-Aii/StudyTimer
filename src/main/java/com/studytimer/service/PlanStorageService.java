package com.studytimer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.studytimer.model.PlanIndexConfig;
import com.studytimer.model.PlanInfo;
import com.studytimer.model.StageConfig;
import com.studytimer.model.TimerStage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlanStorageService {

    private final ObjectMapper objectMapper;

    private final Path appDirectory;
    private final Path plansDirectory;
    private final Path indexFile;
    private final Path legacyPlanFile;

    // 自动备份目录
    private final Path backupsDirectory;

    // 每个文件最多保留 5 份备份
    private static final int MAX_BACKUPS = 5;

    private PlanIndexConfig indexConfig;

    public PlanStorageService() {
        this(defaultAppDirectory());
    }

    /** 指定数据目录（测试或便携模式使用）。 */
    public PlanStorageService(Path appDirectory) {

        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        plansDirectory = appDirectory.resolve("plans");
        indexFile = appDirectory.resolve("plans-index.json");
        backupsDirectory = appDirectory.resolve("backups");

        // 旧版本的单计划文件
        legacyPlanFile = appDirectory.resolve("study-plan.json");
    }

    private static Path defaultAppDirectory() {
        String appData = System.getenv("APPDATA");

        if (appData == null || appData.isBlank()) {
            appData = System.getProperty("user.home");
        }

        if (appData == null || appData.isBlank()) {
            throw new IllegalStateException("无法确定计划文件保存目录");
        }

        return Path.of(appData, "StudyTimer");
    }


    /**
     * 初始化计划存储。
     *
     * 如果新版索引存在但损坏，尝试从历史备份恢复。
     * 如果没有可用备份，则保留原文件并报错。
     */
    public void initialize(List<TimerStage> defaultStages)
            throws IOException {

        Files.createDirectories(plansDirectory);

        // 已有新版计划索引
        if (Files.exists(indexFile)) {

            try {
                indexConfig = readAndValidateIndex(indexFile);

                System.out.println("计划索引加载成功");
                return;

            } catch (IOException indexError) {

                System.out.println("计划索引损坏，尝试从备份恢复");

                try {
                    recoverIndexFromBackup();

                    System.out.println("计划索引恢复成功");
                    return;

                } catch (IOException recoveryError) {

                    recoveryError.addSuppressed(indexError);

                    indexConfig = null;

                    throw new IOException(
                            "计划索引损坏，且没有可用备份。原始文件已保留：" +
                                    indexFile,
                            recoveryError
                    );
                }
            }
        }

        // 尝试迁移旧版计划
        List<TimerStage> initialStages;

        if (Files.exists(legacyPlanFile)) {

            StageConfig[] configs = objectMapper.readValue(
                    legacyPlanFile.toFile(),
                    StageConfig[].class
            );

            initialStages = configsToStages(configs);

            if (initialStages.isEmpty()) {
                initialStages = new ArrayList<>(defaultStages);
            }

            System.out.println("检测到旧版计划，准备迁移");

        } else {
            initialStages = new ArrayList<>(defaultStages);
        }

        indexConfig = new PlanIndexConfig();

        PlanInfo defaultPlan = new PlanInfo(
                newPlanId(),
                "默认计划"
        );

        indexConfig.getPlans().add(defaultPlan);
        indexConfig.setActivePlanId(defaultPlan.getId());

        savePlanFile(defaultPlan.getId(), initialStages);
        saveIndex();

        System.out.println("计划存储初始化成功");
    }


    /**
     * 获取所有计划
     */
    public List<PlanInfo> getPlans() {

        return indexConfig == null
                ? new ArrayList<>()
                : new ArrayList<>(indexConfig.getPlans());
    }

    /**
     * 获取当前激活计划 ID
     */
    public String getActivePlanId() {

        return indexConfig == null
                ? null
                : indexConfig.getActivePlanId();
    }


    /**
     * 加载指定计划。
     *
     * 如果当前计划文件损坏或不存在，
     * 尝试从历史备份中恢复。
     */
    public List<TimerStage> loadPlan(String planId)
            throws IOException {

        requireInitialized();

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        Path file = getPlanPath(planId);

        try {
            if (!Files.isRegularFile(file)) {
                throw new IOException("计划文件不存在或无效：" + file);
            }

            StageConfig[] configs = objectMapper.readValue(
                    file.toFile(),
                    StageConfig[].class
            );

            return configsToStages(configs);

        } catch (IOException loadError) {

            System.out.println(
                    "计划文件读取失败，尝试从备份恢复：" + planId
            );

            try {
                return recoverPlanFromBackup(planId);

            } catch (IOException recoveryError) {

                recoveryError.addSuppressed(loadError);

                throw new IOException(
                        "计划文件损坏或不存在，且无法从历史备份恢复：" +
                                plan.getName(),
                        recoveryError
                );
            }
        }
    }


    /**
     * 从指定计划的历史备份中恢复。
     *
     * 从最新备份开始尝试。
     * 找到有效备份后，先保留当前文件，再恢复。
     */
    private List<TimerStage> recoverPlanFromBackup(String planId)
            throws IOException {

        if (!Files.exists(backupsDirectory)) {
            throw new IOException("计划备份目录不存在");
        }

        String originalFileName = getPlanPath(planId)
                .getFileName()
                .toString();

        String prefix = originalFileName + ".";

        List<Path> backups;

        try (var files = Files.list(backupsDirectory)) {

            backups = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        return name.startsWith(prefix)
                                && name.endsWith(".bak");
                    })
                    .sorted((a, b) ->
                            b.getFileName().toString().compareTo(
                                    a.getFileName().toString()
                            )
                    )
                    .toList();
        }

        if (backups.isEmpty()) {
            throw new IOException("没有找到该计划的历史备份");
        }

        IOException lastError = null;

        // 从最新备份开始逐个尝试
        for (Path backup : backups) {

            try {
                StageConfig[] configs = objectMapper.readValue(
                        backup.toFile(),
                        StageConfig[].class
                );

                List<TimerStage> recoveredStages =
                        configsToStages(configs);

                // 找到有效备份后，恢复当前计划文件
                // savePlanFile 内部使用 writeAtomically，
                // 会先备份当前损坏文件，再覆盖恢复。
                savePlanFile(planId, recoveredStages);

                System.out.println(
                        "计划恢复成功，使用备份：" + backup
                );

                return recoveredStages;

            } catch (IOException e) {

                lastError = e;

                System.out.println(
                        "跳过无效计划备份：" + backup
                );
            }
        }

        throw new IOException(
                "该计划的所有历史备份均无法恢复",
                lastError
        );
    }


    /**
     * 保存指定计划
     */
    public void savePlan(
            String planId,
            List<TimerStage> stages) throws IOException {

        requireInitialized();

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        savePlanFile(planId, stages);
    }

    /**
     * 新建计划
     */
    public PlanInfo createPlan(
            String name,
            List<TimerStage> stages) throws IOException {

        requireInitialized();
        validatePlanName(name);

        String id = newPlanId();

        PlanInfo plan = new PlanInfo(id, name.trim());

        savePlanFile(id, stages);

        indexConfig.getPlans().add(plan);

        try {
            saveIndex();
        } catch (IOException e) {
            indexConfig.getPlans().remove(plan);
            Files.deleteIfExists(getPlanPath(id));
            throw e;
        }

        return plan;
    }

    /**
     * 复制计划
     */
    public PlanInfo duplicatePlan(
            String sourceId,
            String newName) throws IOException {

        List<TimerStage> sourceStages = loadPlan(sourceId);

        return createPlan(newName, sourceStages);
    }

    /**
     * 重命名计划
     */
    public void renamePlan(
            String planId,
            String newName) throws IOException {

        requireInitialized();
        validatePlanName(newName);

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        String oldName = plan.getName();

        plan.setName(newName.trim());

        try {
            saveIndex();
        } catch (IOException e) {
            plan.setName(oldName);
            throw e;
        }
    }


    /**
     * 修改计划描述
     */
    public void updatePlanDescription(
            String planId,
            String description
    ) throws IOException {

        requireInitialized();

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        // 校验描述
        if (description == null) {
            description = "";
        }

        description = description.trim();

        if (description.length() > 300) {
            throw new IllegalArgumentException(
                    "计划描述不能超过 300 个字符"
            );
        }

        // 保存旧描述，以便失败时回滚
        String oldDescription = plan.getDescription();

        plan.setDescription(description);

        try {
            saveIndex();
        } catch (IOException e) {
            plan.setDescription(oldDescription);
            throw e;
        }
    }

    /**
     * 修改计划分类
     */
    public void updatePlanCategory(
            String planId,
            String category
    ) throws IOException {

        requireInitialized();

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        if (category == null || category.isBlank()) {
            category = "未分类";
        }

        category = category.trim();

        if (category.length() > 30) {
            throw new IllegalArgumentException(
                    "计划分类不能超过 30 个字符"
            );
        }

        // 保存旧分类，用于失败回滚
        String oldCategory = plan.getCategory();

        plan.setCategory(category);

        try {
            saveIndex();
        } catch (IOException e) {
            plan.setCategory(oldCategory);
            throw e;
        }
    }

    /**
     * 调整计划在列表中的位置
     *
     * direction:
     * -1：上移
     *  1：下移
     */
    public void movePlan(String planId, int direction)
            throws IOException {

        requireInitialized();

        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException(
                    "移动方向只能是 -1 或 1"
            );
        }

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        List<PlanInfo> plans = indexConfig.getPlans();

        int currentIndex = plans.indexOf(plan);
        int targetIndex = currentIndex + direction;

        // 已经在最顶部或最底部，不需要移动
        if (targetIndex < 0 || targetIndex >= plans.size()) {
            return;
        }

        // 交换两个计划的位置
        PlanInfo targetPlan = plans.get(targetIndex);

        plans.set(targetIndex, plan);
        plans.set(currentIndex, targetPlan);

        try {
            saveIndex();
        } catch (IOException e) {
            // 保存失败，恢复原来的顺序
            plans.set(currentIndex, plan);
            plans.set(targetIndex, targetPlan);
            throw e;
        }
    }


    /**
     * 切换当前计划
     */
    public void setActivePlan(String planId)
            throws IOException {

        requireInitialized();

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        String oldId = indexConfig.getActivePlanId();

        indexConfig.setActivePlanId(planId);

        try {
            saveIndex();
        } catch (IOException e) {
            indexConfig.setActivePlanId(oldId);
            throw e;
        }
    }

    /**
     * 删除计划。
     * 不允许删除最后一套计划。
     */
    public void deletePlan(String planId)
            throws IOException {

        requireInitialized();

        if (indexConfig.getPlans().size() <= 1) {
            throw new IOException("至少需要保留一套计划");
        }

        PlanInfo plan = findPlan(planId);

        if (plan == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        int index = indexConfig.getPlans().indexOf(plan);

        indexConfig.getPlans().remove(plan);

        String oldActiveId = indexConfig.getActivePlanId();

        if (planId.equals(oldActiveId)) {
            indexConfig.setActivePlanId(
                    indexConfig.getPlans().get(0).getId()
            );
        }

        try {
            saveIndex();
        } catch (IOException e) {
            indexConfig.getPlans().add(index, plan);
            indexConfig.setActivePlanId(oldActiveId);
            throw e;
        }

        // 索引更新成功后再删除计划文件
        Files.deleteIfExists(getPlanPath(planId));
    }

    /**
     * 获取计划文件位置
     */
    public Path getPlanFile(String planId) {
        return getPlanPath(planId);
    }

    /**
     * 获取指定计划的历史备份。
     * 按时间从新到旧排列。
     */
    public List<Path> listPlanBackups(String planId) throws IOException {

        requireInitialized();

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        String originalFileName = getPlanPath(planId)
                .getFileName()
                .toString();

        String prefix = originalFileName + ".";

        if (!Files.exists(backupsDirectory)) {
            return new ArrayList<>();
        }

        try (var files = Files.list(backupsDirectory)) {

            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        return name.startsWith(prefix)
                                && name.endsWith(".bak");
                    })
                    .sorted((a, b) ->
                            b.getFileName().toString().compareTo(
                                    a.getFileName().toString()
                            )
                    )
                    .toList();
        }
    }


    /**
     * 读取指定计划的历史备份，用于预览。
     *
     * 只读取文件，不修改当前计划，也不创建备份。
     */
    public List<TimerStage> previewPlanBackup(
            String planId,
            Path backupFile
    ) throws IOException {

        requireInitialized();

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        if (backupFile == null) {
            throw new IllegalArgumentException("备份文件路径不能为空");
        }

        Path normalizedBackup =
                backupFile.toAbsolutePath().normalize();

        Path normalizedBackupDirectory =
                backupsDirectory.toAbsolutePath().normalize();

        // 只允许读取应用自身备份目录中的文件
        if (!normalizedBackupDirectory.equals(
                normalizedBackup.getParent())) {
            throw new IOException("无效的备份文件路径");
        }

        String originalFileName = getPlanPath(planId)
                .getFileName()
                .toString();

        String backupName = normalizedBackup.getFileName()
                .toString();

        // 确保备份文件属于指定计划
        if (!backupName.startsWith(originalFileName + ".")
                || !backupName.endsWith(".bak")) {
            throw new IOException("该备份文件不属于当前计划");
        }

        if (!Files.isRegularFile(normalizedBackup)) {
            throw new IOException("备份文件不存在或无效");
        }

        // 读取历史版本
        StageConfig[] configs = objectMapper.readValue(
                normalizedBackup.toFile(),
                StageConfig[].class
        );

        List<TimerStage> stages = configsToStages(configs);

        if (stages.isEmpty()) {
            throw new IOException("备份文件中没有有效阶段数据");
        }

        return stages;
    }


    /**
     * 从历史备份恢复指定计划。
     *
     * 恢复前会自动备份当前计划文件。
     */
    public void restorePlanBackup(
            String planId,
            Path backupFile
    ) throws IOException {

        requireInitialized();

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        if (backupFile == null) {
            throw new IllegalArgumentException("备份文件路径不能为空");
        }

        Path normalizedBackup = backupFile.toAbsolutePath().normalize();
        Path normalizedBackupDirectory =
                backupsDirectory.toAbsolutePath().normalize();

        // 只允许使用应用自身备份目录中的文件
        if (!normalizedBackup.getParent().equals(normalizedBackupDirectory)) {
            throw new IOException("无效的备份文件路径");
        }

        String originalFileName = getPlanPath(planId)
                .getFileName()
                .toString();

        String backupName = normalizedBackup.getFileName().toString();

        // 确保备份确实属于当前计划
        if (!backupName.startsWith(originalFileName + ".")
                || !backupName.endsWith(".bak")) {
            throw new IOException("该备份文件不属于当前计划");
        }

        if (!Files.isRegularFile(normalizedBackup)) {
            throw new IOException("备份文件不存在或无效");
        }

        // 先读取并校验备份内容，避免用损坏文件覆盖当前计划
        StageConfig[] configs = objectMapper.readValue(
                normalizedBackup.toFile(),
                StageConfig[].class
        );

        List<TimerStage> restoredStages = configsToStages(configs);

        if (restoredStages.isEmpty()) {
            throw new IOException("备份文件中没有有效阶段数据");
        }

        // savePlanFile 最终会调用 writeAtomically：
        // 因此当前文件会先被备份，再被恢复内容覆盖
        savePlanFile(planId, restoredStages);

        System.out.println("计划恢复成功：" + planId);
    }

    /**
     * 导出指定计划到外部 JSON 文件
     */
    public void exportPlan(String planId, Path targetFile)
            throws IOException {

        requireInitialized();

        if (targetFile == null) {
            throw new IllegalArgumentException("导出路径不能为空");
        }

        if (findPlan(planId) == null) {
            throw new IOException("找不到指定计划：" + planId);
        }

        // 转换成普通 Java Bean，避免直接序列化 JavaFX Property
        List<TimerStage> stages = loadPlan(planId);
        List<StageConfig> configs = new ArrayList<>();

        for (TimerStage stage : stages) {

            if (stage == null ||
                    stage.getMinutes() < 1 ||
                    stage.getAudioLeadSeconds() < 1) {
                throw new IOException("当前计划中存在无效阶段");
            }

            StageConfig config = new StageConfig(
                    stage.getId(),
                    stage.getName(),
                    stage.getMinutes(),
                    stage.getAudioFile(),
                    stage.getAudioLeadSeconds(),
                    stage.getPhoneAudioId()
            );
            config.setPhoneVolume(stage.getPhoneVolume());
            configs.add(config);
        }

        // 转成绝对路径，确保可以正确创建父目录
        Path absoluteTarget = targetFile.toAbsolutePath().normalize();

        // 防止误将应用内部的索引或计划文件覆盖
        if (absoluteTarget.equals(indexFile.toAbsolutePath().normalize()) ||
                absoluteTarget.equals(legacyPlanFile.toAbsolutePath().normalize())) {
            throw new IOException("不能覆盖 StudyTimer 内部的计划文件");
        }

        for (PlanInfo plan : indexConfig.getPlans()) {
            if (absoluteTarget.equals(
                    getPlanPath(plan.getId()).toAbsolutePath().normalize())) {
                throw new IOException("不能覆盖 StudyTimer 内部的计划文件");
            }
        }

        writeAtomically(absoluteTarget, configs);
    }

    /**
     * 从外部 JSON 文件导入计划
     * 导入成功后创建一套新计划，不覆盖已有计划
     */
    public PlanInfo importPlan(Path sourceFile, String newName)
            throws IOException {

        requireInitialized();

        if (sourceFile == null) {
            throw new IllegalArgumentException("导入文件路径不能为空");
        }

        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new IOException("导入文件不存在或不是有效文件");
        }

        StageConfig[] configs = objectMapper.readValue(
                sourceFile.toFile(),
                StageConfig[].class
        );

        List<TimerStage> importedStages = configsToStages(configs);

        if (importedStages.isEmpty()) {
            throw new IOException("导入文件中没有阶段数据");
        }

        // createPlan 会负责校验名称、生成新 ID、保存计划和更新索引
        return createPlan(newName, importedStages);
    }

    /**
     * 保存计划文件
     */
    private void savePlanFile(
            String planId,
            List<TimerStage> stages) throws IOException {

        if (stages == null) {
            throw new IllegalArgumentException("阶段列表不能为空");
        }

        List<StageConfig> configs = new ArrayList<>();

        for (TimerStage stage : stages) {

            if (stage == null ||
                    stage.getMinutes() < 1 ||
                    stage.getAudioLeadSeconds() < 1) {
                throw new IllegalArgumentException("存在无效阶段配置");
            }

            StageConfig config = new StageConfig(
                    stage.getId(),
                    stage.getName(),
                    stage.getMinutes(),
                    stage.getAudioFile(),
                    stage.getAudioLeadSeconds(),
                    stage.getPhoneAudioId()
            );
            config.setPhoneVolume(stage.getPhoneVolume());
            configs.add(config);
        }

        writeAtomically(getPlanPath(planId), configs);
    }

    /**
     * 保存索引文件
     */
    private void saveIndex() throws IOException {
        writeAtomically(indexFile, indexConfig);
    }

    /**
     * 读取并验证计划索引。
     */
    private PlanIndexConfig readAndValidateIndex(Path file)
            throws IOException {

        PlanIndexConfig config = objectMapper.readValue(
                file.toFile(),
                PlanIndexConfig.class
        );

        if (config == null ||
                config.getPlans() == null ||
                config.getPlans().isEmpty()) {

            throw new IOException("计划索引为空或无效");
        }

        boolean activePlanExists = false;

        List<String> ids = new ArrayList<>();

        for (PlanInfo plan : config.getPlans()) {

            if (plan == null ||
                    plan.getId() == null ||
                    plan.getId().isBlank()) {

                throw new IOException("计划索引中存在无效计划");
            }

            // 检查重复 ID
            if (ids.contains(plan.getId())) {
                throw new IOException("计划索引中存在重复计划 ID");
            }

            ids.add(plan.getId());

            if (plan.getId().equals(config.getActivePlanId())) {
                activePlanExists = true;
            }
        }

        if (!activePlanExists) {
            throw new IOException("激活计划 ID 不存在于计划列表中");
        }

        return config;
    }


    /**
     * 从最近的有效备份恢复计划索引。
     */
    private void recoverIndexFromBackup() throws IOException {

        if (!Files.exists(backupsDirectory)) {
            throw new IOException("计划备份目录不存在");
        }

        String prefix = indexFile.getFileName().toString() + ".";

        List<Path> backups;

        try (var files = Files.list(backupsDirectory)) {

            backups = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        return name.startsWith(prefix)
                                && name.endsWith(".bak");
                    })
                    .sorted((a, b) ->
                            b.getFileName().toString().compareTo(
                                    a.getFileName().toString()
                            )
                    )
                    .toList();
        }

        if (backups.isEmpty()) {
            throw new IOException("没有找到计划索引备份");
        }

        IOException lastError = null;

        // 从最新备份开始逐个尝试
        for (Path backup : backups) {

            try {
                PlanIndexConfig recovered =
                        readAndValidateIndex(backup);

                // 先设置恢复后的索引
                indexConfig = recovered;

                // 原子写入，覆盖损坏的索引文件
                // writeAtomically 会先备份当前损坏文件
                writeAtomically(indexFile, recovered);

                System.out.println("已从备份恢复索引：" + backup);

                return;

            } catch (IOException e) {

                lastError = e;

                System.out.println(
                        "跳过无效索引备份：" + backup
                );
            }
        }

        indexConfig = null;

        throw new IOException(
                "所有计划索引备份均无法恢复",
                lastError
        );
    }


    /**
     * 备份即将被覆盖的文件。
     */
    private void backupBeforeOverwrite(Path target) throws IOException {

        // 只备份应用内部的计划文件和索引文件，不备份用户导出的外部文件
        Path normalizedTarget = target.toAbsolutePath().normalize();

        boolean isIndexFile = normalizedTarget.equals(
                indexFile.toAbsolutePath().normalize()
        );

        boolean isPlanFile = normalizedTarget.startsWith(
                plansDirectory.toAbsolutePath().normalize()
        );

        if (!isIndexFile && !isPlanFile) {
            return;
        }

        // 新文件没有旧内容，不需要备份
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return;
        }

        Files.createDirectories(backupsDirectory);

        String fileName = target.getFileName().toString();

        // 时间戳加 UUID，避免短时间内连续保存时文件名冲突
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(
                        "yyyyMMdd-HHmmss-SSS"
                ));

        String backupName = fileName + "." + timestamp
                + "-" + UUID.randomUUID().toString().substring(0, 8)
                + ".bak";

        Path backupFile = backupsDirectory.resolve(backupName);

        // 复制旧文件，原文件保持不变
        Files.copy(
                target,
                backupFile,
                StandardCopyOption.COPY_ATTRIBUTES
        );

        System.out.println("计划文件已备份：" + backupFile);

        // 清理旧备份
        cleanOldBackups(fileName);
    }

    /**
     * 每个计划文件只保留最近 MAX_BACKUPS 份备份。
     */
    private void cleanOldBackups(String originalFileName) throws IOException {

        String prefix = originalFileName + ".";

        try (var files = Files.list(backupsDirectory)) {

            List<Path> backups = files
                    .filter(path -> {
                        String name = path.getFileName().toString();

                        return name.startsWith(prefix)
                                && name.endsWith(".bak");
                    })
                    // 文件名包含时间戳，按名称排序即可得到时间顺序
                    .sorted()
                    .toList();

            int deleteCount = backups.size() - MAX_BACKUPS;

            for (int i = 0; i < deleteCount; i++) {
                Files.deleteIfExists(backups.get(i));
            }
        }
    }

    /**
     * 原子写入 JSON 文件。
     */
    private void writeAtomically(Path target, Object data)
            throws IOException {

        Files.createDirectories(target.getParent());

        // 先备份旧文件，再执行覆盖
        backupBeforeOverwrite(target);

        Path tempFile = Files.createTempFile(
                target.getParent(),
                "studytimer-",
                ".tmp"
        );

        try {
            objectMapper.writeValue(tempFile.toFile(), data);

            try {
                Files.move(
                        tempFile,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

            } catch (AtomicMoveNotSupportedException e) {

                Files.move(
                        tempFile,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * StageConfig 转换成 TimerStage
     */
    private List<TimerStage> configsToStages(
            StageConfig[] configs) throws IOException {

        List<TimerStage> stages = new ArrayList<>();

        if (configs == null) {
            return stages;
        }

        for (StageConfig config : configs) {

            if (config == null ||
                    config.getMinutes() < 1 ||
                    config.getAudioLeadSeconds() < 1) {
                throw new IOException("计划文件中存在无效阶段");
            }

            TimerStage stage = new TimerStage(
                    config.getId(),
                    config.getName(),
                    config.getMinutes()
            );

            stage.setAudioFile(config.getAudioFile());
            stage.setAudioLeadSeconds(
                    config.getAudioLeadSeconds()
            );
            stage.setPhoneAudioId(config.getPhoneAudioId());
            stage.setPhoneVolume(config.getPhoneVolume());

            stages.add(stage);
        }

        return stages;
    }

    private PlanInfo findPlan(String planId) {

        return indexConfig.getPlans()
                .stream()
                .filter(plan -> plan.getId().equals(planId))
                .findFirst()
                .orElse(null);
    }

    private Path getPlanPath(String planId) {
        return plansDirectory.resolve(planId + ".json");
    }

    private String newPlanId() {
        return "plan-" + UUID.randomUUID();
    }

    private void validatePlanName(String name) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("计划名称不能为空");
        }

        if (name.trim().length() > 40) {
            throw new IllegalArgumentException("计划名称不能超过 40 个字符");
        }
    }

    private void requireInitialized() {

        if (indexConfig == null) {
            throw new IllegalStateException("请先初始化计划存储服务");
        }
    }
}
