package com.studytimer.ui;

import com.studytimer.model.PlanInfo;
import com.studytimer.model.TimerStage;
import com.studytimer.service.AppLog;
import com.studytimer.service.FocusPlanController;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Focus Plan 的计划管理 UI 与操作逻辑。
 *
 * 由 Main 传入共享的阶段列表与宿主回调；业务规则与提示入口仍由宿主提供，
 * 本类只负责计划选择器、分类栏、描述栏、计划按钮及其动作。
 */
public class FocusPlanPane {

    /** 宿主提供的跨模块能力，避免复制计时/提示等业务逻辑。 */
    public interface Host {
        boolean isTimerRunning();

        void resetTimer();

        void refreshTimerStages();

        void refreshButtonText();

        void updateClock(long remainingSeconds, int currentIndex, int total);

        void resetCurrentStageLabel();

        void showMessage(String message);

        void showWarning(String message);

        void showError(String message);

        void showInformation(String message);

        String errorText(Throwable error);

        String getPhoneAudioName(String audioId);

        /** 已删除（不在下拉框里显示）的计划分类。 */
        List<String> getHiddenPlanCategories();

        void setHiddenPlanCategories(List<String> categories);
    }

    private static final List<String> DEFAULT_CATEGORIES = List.of("未分类", "学习", "工作", "生活", "其他");

    private final ObservableList<TimerStage> stages;
    private final Host host;

    private final FocusPlanController focusPlanController = new FocusPlanController();

    private ComboBox<PlanInfo> planSelector;
    private boolean refreshingPlanSelector = false;
    private ComboBox<String> planCategorySelector;
    private boolean refreshingPlanCategory = false;
    private Label planDescriptionLabel;
    private boolean planDirty = false;
    private Button movePlanUpButton;
    private Button movePlanDownButton;
    private HBox planRow;

    public FocusPlanPane(ObservableList<TimerStage> stages, Host host) {
        this.stages = stages;
        this.host = host;
    }

    public FocusPlanController getFocusPlanController() {
        return focusPlanController;
    }

    public void setPlanDirty(boolean dirty) {
        planDirty = dirty;
    }

    public HBox getPlanRow() {
        return planRow;
    }

    public Label getPlanDescriptionLabel() {
        return planDescriptionLabel;
    }

    /** 构建计划选择器、分类栏、描述栏与计划操作按钮。 */
    public void buildControls() {
        // 初始化计划选择器
        planSelector = new ComboBox<>();

        planSelector.setItems(
                FXCollections.observableArrayList(
                        focusPlanController.getPlans()
                )
        );

        planSelector.setMaxWidth(Double.MAX_VALUE);

        // 显示计划名称，而不是 PlanInfo 对象地址
        planSelector.setConverter(new StringConverter<>() {

            @Override
            public String toString(PlanInfo plan) {
                return plan == null ? "" : plan.getName();
            }

            @Override
            public PlanInfo fromString(String string) {
                return null;
            }
        });

        // 选中当前计划
        String activePlanId = focusPlanController.getActivePlanId();

        for (PlanInfo plan : planSelector.getItems()) {
            if (plan.getId().equals(activePlanId)) {
                planSelector.setValue(plan);
                break;
            }
        }

        // 计划管理按钮
        Button newPlanButton = new Button("新建");
        Button duplicatePlanButton = new Button("复制");
        Button renamePlanButton = new Button("重命名");
        Button deletePlanButton = new Button("删除");

        Button importPlanButton = new Button("导入");
        Button exportPlanButton = new Button("导出");
        Button historyPlanButton = new Button("历史版本");

        deletePlanButton.getStyleClass().add("danger-button");

        movePlanUpButton = new Button("上移");
        movePlanDownButton = new Button("下移");

        movePlanUpButton.setOnAction(e -> moveCurrentPlan(-1));
        movePlanDownButton.setOnAction(e -> moveCurrentPlan(1));

        importPlanButton.setOnAction(e -> importPlanFromFile());
        exportPlanButton.setOnAction(e -> exportCurrentPlan());
        historyPlanButton.setOnAction(e -> showPlanHistory());

        // 绑定事件
        newPlanButton.setOnAction(e -> createNewPlan());
        duplicatePlanButton.setOnAction(e -> duplicateCurrentPlan());
        renamePlanButton.setOnAction(e -> renameCurrentPlan());
        deletePlanButton.setOnAction(e -> deleteCurrentPlan());

        refreshPlanMoveButtons();

        // 切换计划
        planSelector.setOnAction(e -> {

            // 如果是程序主动刷新选择器，则不执行切换
            if (refreshingPlanSelector) {
                return;
            }

            PlanInfo selected = planSelector.getValue();

            if (selected != null) {
                switchPlan(selected);
            }
        });

        // 当前计划描述
        planDescriptionLabel = new Label("暂无描述");
        planDescriptionLabel.setWrapText(true);
        planDescriptionLabel.setMaxWidth(Double.MAX_VALUE);
        planDescriptionLabel.getStyleClass().add("hint-text");

        // 编辑描述按钮
        Button editDescriptionButton = new Button("编辑描述");
        editDescriptionButton.setOnAction(
                e -> editCurrentPlanDescription()
        );

        // 计划分类选择器
        planCategorySelector = new ComboBox<>();
        reloadCategoryItems();

        // 允许用户手动输入分类
        planCategorySelector.setEditable(true);

        planCategorySelector.setPromptText("选择或输入分类");
        planCategorySelector.setPrefWidth(160);

        planCategorySelector.setOnAction(e -> {

            // 程序刷新界面时，不执行保存
            if (refreshingPlanCategory) {
                return;
            }

            PlanInfo currentPlan = planSelector.getValue();

            if (currentPlan == null) {
                return;
            }

            // 获取用户输入的分类
            String category = planCategorySelector.getEditor().getText();

            if (category == null || category.isBlank()) {
                refreshPlanCategory();
                return;
            }

            category = category.trim();

            try {

                // 保存到当前计划
                focusPlanController.updatePlanCategory(
                        currentPlan.getId(),
                        category
                );

                // 重新使用已删除的分类：取消隐藏
                List<String> hidden = new ArrayList<>(host.getHiddenPlanCategories());
                if (hidden.remove(category)) {
                    host.setHiddenPlanCategories(hidden);
                }

                // 如果是新分类，将其加入下拉选项
                if (!planCategorySelector.getItems().contains(category)) {
                    planCategorySelector.getItems().add(category);
                }

                // 更新 ComboBox 的实际 value
                refreshingPlanCategory = true;
                planCategorySelector.setValue(category);
                planCategorySelector.getEditor().setText(category);

            } catch (IOException | IllegalArgumentException ex) {

                host.showError("修改计划分类失败：" + ex.getMessage());

                // 保存失败，恢复原分类
                refreshPlanCategory();

            } finally {
                refreshingPlanCategory = false;
            }
        });

        // 初始化当前计划的分类
        refreshPlanCategory();

        // 计划操作菜单（由 MainView.menuItem 触发已有按钮）
        MenuItem deletePlanItem = MainView.menuItem("删除计划", deletePlanButton);
        deletePlanItem.getStyleClass().add("danger-menu-item");
        Button deleteCategoryButton = new Button("删除分类");
        deleteCategoryButton.setOnAction(e -> deleteCategory());
        MenuButton planMenu = new MenuButton("计划操作");
        planMenu.getItems().addAll(
                MainView.menuItem("新建计划", newPlanButton),
                MainView.menuItem("复制计划", duplicatePlanButton),
                MainView.menuItem("重命名", renamePlanButton),
                MainView.menuItem("编辑描述", editDescriptionButton),
                MainView.menuItem("删除分类…", deleteCategoryButton),
                new SeparatorMenuItem(),
                MainView.menuItem("在列表中上移", movePlanUpButton),
                MainView.menuItem("在列表中下移", movePlanDownButton),
                new SeparatorMenuItem(),
                MainView.menuItem("导入计划…", importPlanButton),
                MainView.menuItem("导出计划…", exportPlanButton),
                MainView.menuItem("历史版本…", historyPlanButton),
                new SeparatorMenuItem(),
                deletePlanItem
        );

        planSelector.setMaxWidth(Double.MAX_VALUE);
        planSelector.setPrefWidth(260);
        HBox.setHgrow(planSelector, Priority.ALWAYS);
        planCategorySelector.setPrefWidth(120);

        planRow = new HBox(10, planSelector, planCategorySelector, planMenu);
        planRow.setAlignment(Pos.CENTER_LEFT);
    }

    /**
     * 保存当前计划
     */
    public void savePlan() {

        saveCurrentPlan(true);
    }

    /**
     * 执行实际保存操作
     */
    private boolean saveCurrentPlan(boolean showSuccess) {

        try {

            String activePlanId =
                    focusPlanController.getActivePlanId();

            if (activePlanId == null) {
                host.showMessage("当前没有激活的计划。");
                return false;
            }

            focusPlanController.saveActivePlan(List.copyOf(stages));

            planDirty = false;

            if (showSuccess) {
                host.showMessage("计划保存成功！");
            }

            return true;

        } catch (IOException | IllegalArgumentException e) {

            AppLog.error("计划保存失败", e);

            host.showMessage("计划保存失败：" + host.errorText(e));

            return false;
        }
    }

    /**
     * 刷新计划选择器
     */
    private void refreshPlanSelector(String selectedPlanId) {

        refreshingPlanSelector = true;

        try {

            planSelector.getItems().setAll(
                    focusPlanController.getPlans()
            );

            for (PlanInfo plan : planSelector.getItems()) {

                if (plan.getId().equals(selectedPlanId)) {
                    planSelector.setValue(plan);
                    refreshPlanMoveButtons();
                    refreshPlanDescription();
                    refreshPlanCategory();
                    return;
                }
            }

            planSelector.setValue(null);

            refreshPlanMoveButtons();
            refreshPlanDescription();
            refreshPlanCategory();

        } finally {
            refreshingPlanSelector = false;
        }
    }

    /**
     * 刷新当前计划描述
     */
    private void refreshPlanDescription() {

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            planDescriptionLabel.setText("暂无描述");
            return;
        }

        String description = currentPlan.getDescription();

        if (description == null || description.isBlank()) {
            planDescriptionLabel.setText("暂无描述");
        } else {
            planDescriptionLabel.setText(description);
        }
    }

    /** 下拉选项 = 默认分类 + 所有计划用到的分类，去掉已删除的（仍被计划使用的不隐藏）。 */
    private void reloadCategoryItems() {
        java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>();
        List<String> hidden = host.getHiddenPlanCategories();
        for (String c : DEFAULT_CATEGORIES) {
            if ("未分类".equals(c) || !hidden.contains(c)) {
                items.add(c);
            }
        }
        for (PlanInfo plan : focusPlanController.getPlans()) {
            items.add(plan.getCategory());
        }
        boolean old = refreshingPlanCategory;
        refreshingPlanCategory = true;
        try {
            planCategorySelector.getItems().setAll(items);
        } finally {
            refreshingPlanCategory = old;
        }
    }

    /** 删除一个分类：使用它的计划改为“未分类”，并从下拉框中移除。 */
    private void deleteCategory() {
        List<String> choices = new ArrayList<>(planCategorySelector.getItems());
        choices.remove("未分类");
        if (choices.isEmpty()) {
            host.showInformation("没有可删除的分类。");
            return;
        }
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("删除分类");
        dialog.setHeaderText(null);
        dialog.setContentText("要删除的分类：");
        if (planSelector.getScene() != null) {
            dialog.initOwner(planSelector.getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(planSelector.getScene().getStylesheets());
        }
        String category = dialog.showAndWait().orElse(null);
        if (category == null) {
            return;
        }
        List<PlanInfo> using = focusPlanController.getPlans().stream()
                .filter(p -> category.equals(p.getCategory())).toList();
        if (!using.isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    using.size() + " 个计划使用分类“" + category + "”，删除后它们改为“未分类”。继续吗？",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(null);
            confirm.setTitle("删除分类");
            if (planSelector.getScene() != null) {
                confirm.initOwner(planSelector.getScene().getWindow());
                confirm.getDialogPane().getStylesheets().addAll(planSelector.getScene().getStylesheets());
            }
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        try {
            for (PlanInfo plan : using) {
                focusPlanController.updatePlanCategory(plan.getId(), "未分类");
            }
        } catch (IOException | IllegalArgumentException ex) {
            host.showError("删除分类失败：" + ex.getMessage());
        }
        List<String> hidden = new ArrayList<>(host.getHiddenPlanCategories());
        if (!hidden.contains(category)) {
            hidden.add(category);
            host.setHiddenPlanCategories(hidden);
        }
        reloadCategoryItems();
        refreshPlanCategory();
        host.showMessage("已删除分类“" + category + "”");
    }

    /**
     * 刷新当前计划分类
     */
    private void refreshPlanCategory() {

        if (planCategorySelector == null) {
            return;
        }

        refreshingPlanCategory = true;

        try {

            PlanInfo currentPlan = planSelector.getValue();

            if (currentPlan == null) {
                planCategorySelector.setValue(null);
                planCategorySelector.getEditor().clear();
                planCategorySelector.setDisable(true);
                return;
            }

            planCategorySelector.setDisable(false);

            String category = currentPlan.getCategory();

            // 如果是自定义分类，则加入下拉列表
            if (!planCategorySelector.getItems().contains(category)) {
                planCategorySelector.getItems().add(category);
            }

            planCategorySelector.setValue(category);

            // 同步编辑框文本
            planCategorySelector.getEditor().setText(category);

        } finally {
            refreshingPlanCategory = false;
        }
    }

    /**
     * 检查当前计划是否有未保存修改。
     *
     * 返回 true：允许继续操作
     * 返回 false：取消操作
     */
    private boolean resolveUnsavedChanges() {

        if (!planDirty) {
            return true;
        }

        ButtonType saveButton = new ButtonType(
                "保存",
                ButtonBar.ButtonData.YES
        );

        ButtonType discardButton = new ButtonType(
                "放弃修改",
                ButtonBar.ButtonData.NO
        );

        ButtonType cancelButton = new ButtonType(
                "取消",
                ButtonBar.ButtonData.CANCEL_CLOSE
        );

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);

        alert.setTitle("未保存的修改");
        alert.setHeaderText("当前计划存在未保存的修改");
        alert.setContentText("是否保存当前计划后继续？");

        alert.getButtonTypes().setAll(
                saveButton,
                discardButton,
                cancelButton
        );

        ButtonType result = alert.showAndWait()
                .orElse(cancelButton);

        if (result == saveButton) {
            return saveCurrentPlan(false);
        }

        if (result == discardButton) {

            try {
                // 从磁盘重新读取当前计划
                List<TimerStage> savedStages =
                        focusPlanController.loadActivePlan();

                stages.setAll(savedStages);

                host.refreshTimerStages();
                host.resetTimer();

                planDirty = false;

                host.refreshButtonText();

                host.resetCurrentStageLabel();

                if (!stages.isEmpty()) {
                    host.updateClock(
                            stages.get(0).getMinutes() * 60L,
                            0,
                            stages.size()
                    );
                } else {
                    host.updateClock(0, 0, 0);
                }
                return true;

            } catch (IOException e) {
                host.showMessage("恢复计划失败：" + e.getMessage());
                return false;
            }
        }

        return false;
    }

    /**
     * 切换当前计划
     */
    private void switchPlan(PlanInfo targetPlan) {

        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再切换计划。");

            refreshPlanSelector(
                    focusPlanController.getActivePlanId()
            );

            return;
        }

        String currentId = focusPlanController.getActivePlanId();

        if (targetPlan.getId().equals(currentId)) {
            return;
        }

        // 处理未保存修改
        if (!resolveUnsavedChanges()) {

            refreshPlanSelector(currentId);

            return;
        }

        try {

            // 由控制器负责加载目标计划并切换激活状态
            List<TimerStage> newStages =
                    focusPlanController.switchActivePlan(targetPlan.getId());

            // 更新 JavaFX 阶段列表
            stages.setAll(newStages);

            // 更新计时引擎
            host.refreshTimerStages();

            // 重置计时状态
            host.resetTimer();

            host.refreshButtonText();

            planDirty = false;

            refreshPlanSelector(targetPlan.getId());

            if (!stages.isEmpty()) {
                host.updateClock(
                        stages.get(0).getMinutes() * 60L,
                        0,
                        stages.size()
                );
                host.resetCurrentStageLabel();
            } else {
                host.updateClock(0, 0, 0);
                host.resetCurrentStageLabel();
            }

            System.out.println(
                    "已切换计划：" + targetPlan.getName()
            );

        } catch (IOException e) {

            AppLog.error("切换计划失败", e);

            refreshPlanSelector(currentId);

            host.showMessage("切换计划失败：" + host.errorText(e));
        }
    }

    /**
     * 创建新计划
     */
    private void createNewPlan() {

        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再创建计划。");
            return;
        }

        if (!resolveUnsavedChanges()) {
            return;
        }

        TextInputDialog dialog =
                new TextInputDialog("新计划");

        dialog.setTitle("新建计划");
        dialog.setHeaderText("创建一套新的学习计划");
        dialog.setContentText("计划名称：");

        dialog.showAndWait().ifPresent(name -> {

            try {

                List<TimerStage> defaultStages = List.of(
                        new TimerStage("专注", 25),
                        new TimerStage("休息", 5)
                );

                FocusPlanController.ActivePlan created =
                        focusPlanController.createAndActivate(name, defaultStages);
                PlanInfo newPlan = created.info();

                stages.setAll(created.stages());

                host.refreshTimerStages();

                host.resetTimer();

                host.refreshButtonText();

                planDirty = false;

                refreshPlanSelector(newPlan.getId());

                host.updateClock(25 * 60, 0, 2);
                host.resetCurrentStageLabel();

                host.showMessage("新计划创建成功！");

            } catch (IOException | IllegalArgumentException e) {

                host.showMessage("创建计划失败：" + e.getMessage());
            }
        });
    }

    /**
     * 复制当前计划
     */
    private void duplicateCurrentPlan() {

        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再复制计划。");
            return;
        }

        if (!resolveUnsavedChanges()) {
            return;
        }

        PlanInfo currentPlan = focusPlanController.getActivePlanInfo();

        if (currentPlan == null) {
            host.showMessage("找不到当前计划。");
            return;
        }

        TextInputDialog dialog =
                new TextInputDialog(currentPlan.getName() + " - 副本");

        dialog.setTitle("复制计划");
        dialog.setHeaderText("复制当前计划的所有阶段配置");
        dialog.setContentText("新计划名称：");

        dialog.showAndWait().ifPresent(name -> {

            try {

                FocusPlanController.ActivePlan duplicated =
                        focusPlanController.duplicateActiveAndActivate(name);
                PlanInfo newPlan = duplicated.info();

                stages.setAll(duplicated.stages());

                host.refreshTimerStages();

                host.resetTimer();

                host.refreshButtonText();

                planDirty = false;

                refreshPlanSelector(newPlan.getId());

                if (!stages.isEmpty()) {
                    host.updateClock(
                            stages.get(0).getMinutes() * 60L,
                            0,
                            stages.size()
                    );
                }

                host.resetCurrentStageLabel();

                host.showMessage("计划复制成功！");

            } catch (IOException | IllegalArgumentException e) {

                host.showMessage("复制计划失败：" + e.getMessage());
            }
        });
    }

    /**
     * 重命名当前计划
     */
    private void renameCurrentPlan() {

        if (!resolveUnsavedChanges()) {
            return;
        }

        String activeId = focusPlanController.getActivePlanId();
        PlanInfo currentPlan = focusPlanController.getActivePlanInfo();

        if (currentPlan == null) {
            host.showMessage("找不到当前计划。");
            return;
        }

        TextInputDialog dialog =
                new TextInputDialog(currentPlan.getName());

        dialog.setTitle("重命名计划");
        dialog.setHeaderText("修改计划名称");
        dialog.setContentText("新名称：");

        dialog.showAndWait().ifPresent(name -> {

            try {

                focusPlanController.renameActivePlan(name);

                refreshPlanSelector(activeId);

            } catch (IOException | IllegalArgumentException e) {

                host.showMessage("重命名失败：" + e.getMessage());
            }
        });
    }

    /**
     * 编辑当前计划描述
     */
    private void editCurrentPlanDescription() {

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            host.showWarning("当前没有选中的计划。");
            return;
        }

        TextArea descriptionArea = new TextArea(
                currentPlan.getDescription()
        );

        descriptionArea.setPromptText("请输入计划描述...");
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(5);

        Dialog<String> dialog = new Dialog<>();

        dialog.setTitle("编辑计划描述");
        dialog.setHeaderText(
                "为「" + currentPlan.getName() + "」添加描述"
        );

        dialog.initOwner(
                planSelector.getScene().getWindow()
        );

        ButtonType saveButton = new ButtonType(
                "保存",
                ButtonBar.ButtonData.OK_DONE
        );

        dialog.getDialogPane().getButtonTypes().addAll(
                saveButton,
                ButtonType.CANCEL
        );

        dialog.getDialogPane().setContent(descriptionArea);

        dialog.setResultConverter(button -> {
            if (button == saveButton) {
                return descriptionArea.getText();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(description -> {

            try {

                focusPlanController.updatePlanDescription(
                        currentPlan.getId(),
                        description
                );

                // 重新获取计划信息并刷新描述
                refreshPlanSelector(currentPlan.getId());

                host.showInformation("计划描述保存成功！");

            } catch (IOException | IllegalArgumentException e) {

                host.showError("保存计划描述失败：" + e.getMessage());
            }
        });
    }

    /**
     * 删除当前计划
     */
    private void deleteCurrentPlan() {

        if (host.isTimerRunning()) {
            host.showMessage("请先暂停计时，再删除计划。");
            return;
        }

        if (!resolveUnsavedChanges()) {
            return;
        }

        if (focusPlanController.getPlans().size() <= 1) {
            host.showMessage("至少需要保留一套计划。");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);

        alert.setTitle("删除计划");
        alert.setHeaderText("确定删除当前计划吗？");
        alert.setContentText("删除后，该计划的配置文件也会被删除。");

        ButtonType result = alert.showAndWait()
                .orElse(ButtonType.CANCEL);

        if (result != ButtonType.OK) {
            return;
        }

        try {

            FocusPlanController.ActivePlan replacement =
                    focusPlanController.deleteActivePlan();
            String newActiveId = replacement.info().getId();

            stages.setAll(replacement.stages());

            host.refreshTimerStages();

            host.resetTimer();

            host.refreshButtonText();

            planDirty = false;

            refreshPlanSelector(newActiveId);

            if (!stages.isEmpty()) {
                host.updateClock(
                        stages.get(0).getMinutes() * 60L,
                        0,
                        stages.size()
                );
            } else {
                host.updateClock(0, 0, 0);
            }

            host.resetCurrentStageLabel();

            host.showMessage("计划删除成功！");

        } catch (IOException e) {

            host.showMessage("删除计划失败：" + e.getMessage());
        }
    }

    /**
     * 移动当前计划
     *
     * direction:
     * -1：上移
     *  1：下移
     */
    private void moveCurrentPlan(int direction) {

        if (host.isTimerRunning()) {
            host.showWarning("请先暂停计时，再调整计划顺序");
            return;
        }

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            return;
        }

        List<PlanInfo> plans = focusPlanController.getPlans();

        int currentIndex = -1;

        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).getId().equals(currentPlan.getId())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) {
            return;
        }

        int targetIndex = currentIndex + direction;

        // 已经到达边界
        if (targetIndex < 0 || targetIndex >= plans.size()) {
            return;
        }

        try {
            focusPlanController.movePlan(
                    currentPlan.getId(),
                    direction
            );

            refreshPlanSelector(currentPlan.getId());

        } catch (IOException | IllegalArgumentException e) {
            host.showError("调整计划顺序失败：" + e.getMessage());
        }
    }

    /**
     * 根据当前计划的位置更新排序按钮状态
     */
    private void refreshPlanMoveButtons() {

        if (planSelector == null ||
                movePlanUpButton == null ||
                movePlanDownButton == null) {
            return;
        }

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            movePlanUpButton.setDisable(true);
            movePlanDownButton.setDisable(true);
            return;
        }

        List<PlanInfo> plans = focusPlanController.getPlans();

        int index = -1;

        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).getId().equals(currentPlan.getId())) {
                index = i;
                break;
            }
        }

        movePlanUpButton.setDisable(index <= 0);
        movePlanDownButton.setDisable(
                index == -1 || index >= plans.size() - 1
        );
    }

    /**
     * 查看、预览并恢复当前计划的历史版本
     */
    private void showPlanHistory() {

        if (host.isTimerRunning()) {
            host.showWarning("请先暂停计时，再恢复历史版本。");
            return;
        }

        if (!resolveUnsavedChanges()) {
            return;
        }

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            host.showWarning("当前没有选中的计划。");
            return;
        }

        String planId = currentPlan.getId();

        try {

            List<Path> backups =
                    focusPlanController.listPlanBackups(planId);

            if (backups.isEmpty()) {
                host.showInformation("当前计划还没有历史备份。");
                return;
            }

            // 创建对话框
            Dialog<Path> dialog = new Dialog<>();

            dialog.setTitle("计划历史版本");
            dialog.setHeaderText(
                    "当前计划：" + currentPlan.getName()
            );

            dialog.initOwner(
                    planSelector.getScene().getWindow()
            );

            dialog.setResizable(true);

            // 左侧：历史版本列表
            ListView<Path> historyList = new ListView<>();

            historyList.getItems().setAll(backups);
            historyList.getSelectionModel().selectFirst();

            historyList.setCellFactory(listView ->
                    new ListCell<>() {

                        @Override
                        protected void updateItem(
                                Path path,
                                boolean empty) {

                            super.updateItem(path, empty);

                            if (empty || path == null) {
                                setText(null);
                            } else {
                                setText(formatBackupTime(path));
                            }
                        }
                    }
            );

            historyList.setPrefWidth(190);
            historyList.setPrefHeight(280);

            // 右侧：历史版本详情
            ListView<String> previewList = new ListView<>();

            previewList.setPrefWidth(300);
            previewList.setPrefHeight(280);

            Label previewTitle = new Label("版本内容");

            VBox previewBox = new VBox(
                    8,
                    previewTitle,
                    previewList
            );

            previewBox.setPrefWidth(310);
            previewBox.setFillWidth(true);

            Label historyTitle = new Label("历史记录");

            VBox historyBox = new VBox(
                    8,
                    historyTitle,
                    historyList
            );

            historyBox.setPrefWidth(200);
            historyBox.setFillWidth(true);

            // 左右组合
            HBox content = new HBox(
                    12,
                    historyBox,
                    previewBox
            );

            content.setPrefSize(530, 310);

            dialog.getDialogPane().setContent(content);

            // 设置按钮
            ButtonType restoreButton = new ButtonType(
                    "恢复此版本",
                    ButtonBar.ButtonData.OK_DONE
            );

            dialog.getDialogPane().getButtonTypes().setAll(
                    restoreButton,
                    ButtonType.CANCEL
            );

            // 选择历史记录时，自动读取并展示阶段详情

            historyList.getSelectionModel()
                    .selectedItemProperty()
                    .addListener((observable, oldValue, newValue) -> {

                        previewList.getItems().clear();

                        if (newValue == null) {
                            return;
                        }

                        try {

                            // 读取所选历史版本
                            List<TimerStage> historyStages =
                                    focusPlanController.previewPlanBackup(
                                            planId,
                                            newValue
                                    );

                            // 与当前计划进行比较
                            showPlanDiff(
                                    stages,
                                    historyStages,
                                    previewList
                            );

                        } catch (IOException | RuntimeException e) {

                            previewList.getItems().setAll(
                                    "无法读取此历史版本：",
                                    e.getMessage() == null
                                            ? "未知错误"
                                            : e.getMessage()
                            );
                        }
                    });

            // 主动触发一次初始预览
            Path firstBackup = historyList.getSelectionModel()
                    .getSelectedItem();

            if (firstBackup != null) {
                historyList.getSelectionModel().select(null);
                historyList.getSelectionModel().select(firstBackup);
            }

            // 确定后返回被选中的历史文件
            dialog.setResultConverter(button -> {

                if (button == restoreButton) {
                    return historyList.getSelectionModel()
                            .getSelectedItem();
                }

                return null;
            });

            // 处理恢复操作
            dialog.showAndWait().ifPresent(backupFile -> {

                Alert confirm = new Alert(
                        Alert.AlertType.CONFIRMATION
                );

                confirm.setTitle("确认恢复");
                confirm.setHeaderText("确定恢复这个历史版本吗？");

                confirm.setContentText(
                        "恢复后，当前计划内容将被替换。\n" +
                                "当前版本会自动备份，可以之后再次恢复。"
                );

                confirm.initOwner(
                        planSelector.getScene().getWindow()
                );

                ButtonType result = confirm.showAndWait()
                        .orElse(ButtonType.CANCEL);

                if (result != ButtonType.OK) {
                    return;
                }

                try {

                    focusPlanController.restorePlanBackup(
                            planId,
                            backupFile
                    );

                    List<TimerStage> restoredStages =
                            focusPlanController.loadPlan(planId);

                    stages.setAll(restoredStages);

                    host.refreshTimerStages();

                    host.resetTimer();

                    host.refreshButtonText();

                    planDirty = false;

                    host.resetCurrentStageLabel();

                    if (!stages.isEmpty()) {
                        host.updateClock(
                                stages.get(0).getMinutes() * 60L,
                                0,
                                stages.size()
                        );
                    } else {
                        host.updateClock(0, 0, 0);
                    }

                    host.showInformation("历史版本恢复成功！");

                } catch (IOException | RuntimeException ex) {

                    AppLog.error("恢复历史版本失败", ex);

                    host.showError(
                            "恢复历史版本失败：\n" + host.errorText(ex)
                    );
                }
            });

        } catch (IOException | RuntimeException e) {

            AppLog.error("读取历史版本失败", e);

            host.showError(
                    "读取历史版本失败：\n" + host.errorText(e)
            );
        }
    }

    /**
     * 比较当前计划和历史版本
     *
     * 根据阶段唯一 ID 匹配阶段，不再依赖阶段位置。
     */
    private void showPlanDiff(
            List<TimerStage> currentStages,
            List<TimerStage> historyStages,
            ListView<String> previewList
    ) {

        previewList.getItems().clear();

        previewList.getItems().add("当前阶段数：" + currentStages.size());
        previewList.getItems().add("历史阶段数：" + historyStages.size());
        previewList.getItems().add("");

        // 1. 建立 ID -> 阶段 的映射
        Map<String, TimerStage> currentMap = new HashMap<>();
        Map<String, TimerStage> historyMap = new HashMap<>();

        // 记录每个阶段在两个版本中的位置
        Map<String, Integer> currentPositions = new HashMap<>();
        Map<String, Integer> historyPositions = new HashMap<>();

        for (int i = 0; i < currentStages.size(); i++) {
            TimerStage stage = currentStages.get(i);

            currentMap.put(stage.getId(), stage);
            currentPositions.put(stage.getId(), i);
        }

        for (int i = 0; i < historyStages.size(); i++) {
            TimerStage stage = historyStages.get(i);

            historyMap.put(stage.getId(), stage);
            historyPositions.put(stage.getId(), i);
        }

        // 2. 判断哪些共同阶段发生了顺序变化
        Set<String> movedIds = new HashSet<>();

        for (int i = 0; i < currentStages.size(); i++) {

            String idA = currentStages.get(i).getId();

            // 历史版本中不存在，不参与顺序比较
            if (!historyMap.containsKey(idA)) {
                continue;
            }

            for (int j = i + 1; j < currentStages.size(); j++) {

                String idB = currentStages.get(j).getId();

                // 只比较两个版本都存在的阶段
                if (!historyMap.containsKey(idB)) {
                    continue;
                }

                int currentOrder = Integer.compare(
                        currentPositions.get(idA),
                        currentPositions.get(idB)
                );

                int historyOrder = Integer.compare(
                        historyPositions.get(idA),
                        historyPositions.get(idB)
                );

                // 两个阶段的相对顺序发生变化
                if (currentOrder != historyOrder) {
                    movedIds.add(idA);
                    movedIds.add(idB);
                }
            }
        }

        int changedCount = 0;
        int movedCount = movedIds.size();
        int currentAddedCount = 0;
        int currentDeletedCount = 0;

        // 3. 以历史版本为基准，逐个检查历史阶段
        previewList.getItems().add("========== 历史版本阶段 ==========");

        for (int i = 0; i < historyStages.size(); i++) {

            TimerStage history = historyStages.get(i);

            previewList.getItems().add(
                    "========== 历史阶段 " + (i + 1) + " =========="
            );

            TimerStage current = currentMap.get(history.getId());

            // 历史中存在，但当前版本已经不存在
            if (current == null) {

                previewList.getItems().add(
                        "[当前版本已删除此阶段]"
                );

                previewList.getItems().add(
                        "历史名称：" + history.getName()
                );

                previewList.getItems().add(
                        "历史时长：" + history.getMinutes() + " 分钟"
                );

                previewList.getItems().add(
                        "历史音乐：" + getAudioFileName(history.getAudioFile())
                );

                previewList.getItems().add(
                        "历史手机音频：" + host.getPhoneAudioName(history.getPhoneAudioId())
                );

                previewList.getItems().add(
                        "历史提前播放：" + history.getAudioLeadSeconds() + " 秒"
                );

                currentDeletedCount++;

                previewList.getItems().add("");
                continue;
            }

            boolean changed = false;

            // 比较阶段名称
            if (!Objects.equals(
                    current.getName(),
                    history.getName())) {

                previewList.getItems().add(
                        "名称：当前「" + current.getName() +
                                "」 → 历史「" + history.getName() + "」"
                );

                changed = true;
            }

            // 比较阶段时长
            if (current.getMinutes() != history.getMinutes()) {

                previewList.getItems().add(
                        "时长：当前 " + current.getMinutes() +
                                " 分钟 → 历史 " +
                                history.getMinutes() + " 分钟"
                );

                changed = true;
            }

            // 比较音乐文件
            if (!Objects.equals(
                    current.getAudioFile(),
                    history.getAudioFile())) {

                previewList.getItems().add(
                        "音乐：当前「" +
                                getAudioFileName(current.getAudioFile()) +
                                "」 → 历史「" +
                                getAudioFileName(history.getAudioFile()) +
                                "」"
                );

                changed = true;
            }

            // 比较手机音频
            if (!Objects.equals(
                    current.getPhoneAudioId(),
                    history.getPhoneAudioId())) {

                previewList.getItems().add(
                        "手机音频：当前「" +
                                host.getPhoneAudioName(current.getPhoneAudioId()) +
                                "」 → 历史「" +
                                host.getPhoneAudioName(history.getPhoneAudioId()) +
                                "」"
                );

                changed = true;
            }

            // 比较提前播放时间
            if (current.getAudioLeadSeconds()
                    != history.getAudioLeadSeconds()) {

                previewList.getItems().add(
                        "提前播放：当前 " +
                                current.getAudioLeadSeconds() +
                                " 秒 → 历史 " +
                                history.getAudioLeadSeconds() + " 秒"
                );

                changed = true;
            }

            if (changed) {
                previewList.getItems().add("[配置已修改]");
                changedCount++;
            }

            if (movedIds.contains(history.getId())) {
                previewList.getItems().add(
                        "[阶段顺序发生变化]"
                );
            }

            if (!changed && !movedIds.contains(history.getId())) {
                previewList.getItems().add("[没有变化]");
            }

            previewList.getItems().add("");
        }

        // 4. 检查当前版本新增的阶段
        previewList.getItems().add("========== 当前版本新增阶段 ==========");

        for (int i = 0; i < currentStages.size(); i++) {

            TimerStage current = currentStages.get(i);

            // 历史版本中不存在这个 ID，说明是后来新增的阶段
            if (!historyMap.containsKey(current.getId())) {

                previewList.getItems().add(
                        "[当前版本新增阶段]"
                );

                previewList.getItems().add(
                        "位置：" + (i + 1)
                );

                previewList.getItems().add(
                        "名称：" + current.getName()
                );

                previewList.getItems().add(
                        "时长：" + current.getMinutes() + " 分钟"
                );

                previewList.getItems().add(
                        "音乐：" + getAudioFileName(current.getAudioFile())
                );

                previewList.getItems().add(
                        "手机音频：" + host.getPhoneAudioName(current.getPhoneAudioId())
                );

                previewList.getItems().add(
                        "提前播放：" + current.getAudioLeadSeconds() + " 秒"
                );

                currentAddedCount++;

                previewList.getItems().add("");
            }
        }

        // 5. 差异汇总
        previewList.getItems().add("========== 差异汇总 ==========");

        previewList.getItems().add(
                "配置修改阶段：" + changedCount
        );

        previewList.getItems().add(
                "顺序变化阶段：" + movedCount
        );

        previewList.getItems().add(
                "当前版本新增：" + currentAddedCount
        );

        previewList.getItems().add(
                "当前版本已删除：" + currentDeletedCount
        );

        if (changedCount == 0 &&
                movedCount == 0 &&
                currentAddedCount == 0 &&
                currentDeletedCount == 0) {

            previewList.getItems().add("");
            previewList.getItems().add(
                    "当前计划与历史版本完全一致。"
            );
        }
    }

    /**
     * 将备份文件的最后修改时间格式化为可读形式
     */
    private String formatBackupTime(Path backupFile) {

        try {
            return java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(
                            java.nio.file.Files
                                    .getLastModifiedTime(backupFile)
                                    .toInstant()
                    );

        } catch (IOException e) {
            return backupFile.getFileName().toString();
        }
    }

    /**
     * 导出当前计划
     */
    private void exportCurrentPlan() {

        if (host.isTimerRunning()) {
            host.showWarning("请先暂停计时，再导出计划");
            return;
        }

        // 处理未保存修改，确保导出的内容是当前确认过的数据
        if (!resolveUnsavedChanges()) {
            return;
        }

        PlanInfo currentPlan = planSelector.getValue();

        if (currentPlan == null) {
            host.showWarning("当前没有选中的计划");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出计划");

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "StudyTimer 计划文件 (*.json)",
                        "*.json"
                )
        );

        fileChooser.setInitialFileName(
                currentPlan.getName() + ".json"
        );

        File file = fileChooser.showSaveDialog(
                planSelector.getScene().getWindow()
        );

        if (file == null) {
            return;
        }

        try {
            focusPlanController.exportPlan(
                    currentPlan.getId(),
                    Path.of(file.getAbsolutePath())
            );

            host.showInformation("计划导出成功！\n" + file.getAbsolutePath());

        } catch (IOException | IllegalArgumentException e) {
            host.showError("计划导出失败：" + e.getMessage());
        }
    }

    /**
     * 从外部 JSON 文件导入计划
     */
    private void importPlanFromFile() {

        if (host.isTimerRunning()) {
            host.showWarning("请先暂停计时，再导入计划");
            return;
        }

        // 先处理当前计划的未保存修改
        if (!resolveUnsavedChanges()) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择要导入的计划");

        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "StudyTimer 计划文件 (*.json)",
                        "*.json"
                )
        );

        File file = fileChooser.showOpenDialog(
                planSelector.getScene().getWindow()
        );

        if (file == null) {
            return;
        }

        // 默认使用文件名作为新计划名称
        String defaultName = file.getName();

        if (defaultName.toLowerCase().endsWith(".json")) {
            defaultName = defaultName.substring(
                    0, defaultName.length() - 5
            );
        }

        TextInputDialog dialog = new TextInputDialog(defaultName);
        dialog.setTitle("导入计划");
        dialog.setHeaderText("请输入新计划名称");
        dialog.setContentText("计划名称：");

        dialog.showAndWait().ifPresent(name -> {

            try {
                PlanInfo importedPlan = focusPlanController.importPlan(
                        Path.of(file.getAbsolutePath()),
                        name
                );

                // 更新计划选择器，但不切换当前激活计划
                refreshPlanSelector(
                        focusPlanController.getActivePlanId()
                );

                host.showInformation(
                        "计划导入成功！\n新计划：" +
                                importedPlan.getName()
                );

            } catch (IOException | IllegalArgumentException e) {
                host.showError("计划导入失败：" + e.getMessage());
            }
        });
    }

    /**
     * 获取音乐文件名用于界面展示
     */
    private String getAudioFileName(String audioFile) {

        if (audioFile == null || audioFile.isBlank()) {
            return "无";
        }

        try {
            return Path.of(audioFile)
                    .getFileName()
                    .toString();

        } catch (RuntimeException e) {
            return audioFile;
        }
    }
}
