package com.studytimer.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 可整体收起的面板：展开时显示标题栏和内容，收起后变成一条带竖排标题的窄栏。
 */
public final class CollapsiblePanel extends StackPane {

    public static final double COLLAPSED_WIDTH = 46;

    private final BooleanProperty expanded = new SimpleBooleanProperty(true);
    private final HBox headerTools = new HBox(8);
    private final VBox expandedView;
    private final VBox collapsedView;
    private final Button collapseButton;

    /**
     * @param collapseTowardsLeft 面板位于左侧时为 true，决定箭头方向
     */
    public CollapsiblePanel(String title, Node content, boolean collapseTowardsLeft) {
        getStyleClass().addAll("surface-card", "collapsible-panel");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("panel-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        headerTools.setAlignment(Pos.CENTER_RIGHT);

        collapseButton = new Button(collapseTowardsLeft ? "«" : "»");
        collapseButton.getStyleClass().add("icon-button");
        collapseButton.setTooltip(new Tooltip("收起"));
        collapseButton.setOnAction(e -> setExpanded(false));

        HBox header = new HBox(10, titleLabel, spacer, headerTools, collapseButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("panel-header");

        VBox.setVgrow(content, Priority.ALWAYS);
        expandedView = new VBox(14, header, content);
        expandedView.setPadding(new Insets(12, 14, 14, 14));

        Button expandButton = new Button(collapseTowardsLeft ? "»" : "«");
        expandButton.getStyleClass().add("icon-button");
        expandButton.setTooltip(new Tooltip("展开 " + title));
        expandButton.setOnAction(e -> setExpanded(true));

        Label verticalTitle = new Label(title);
        verticalTitle.getStyleClass().add("panel-title");
        verticalTitle.setRotate(collapseTowardsLeft ? -90 : 90);

        collapsedView = new VBox(18, expandButton, new Group(verticalTitle));
        collapsedView.setAlignment(Pos.TOP_CENTER);
        collapsedView.setPadding(new Insets(10, 0, 10, 0));
        collapsedView.getStyleClass().add("panel-collapsed");
        // 收起后点击整条窄栏都可以展开
        collapsedView.setOnMouseClicked(e -> setExpanded(true));

        getChildren().addAll(expandedView, collapsedView);
        expanded.addListener((obs, oldValue, value) -> applyState());
        applyState();
    }

    /** 标题栏右侧的附加控件（例如数量、快捷按钮）。 */
    public void addHeaderTools(Node... nodes) {
        headerTools.getChildren().addAll(nodes);
    }

    /** 改变标题栏右上角按钮的文字、提示和动作（默认是收起本面板）。 */
    public void setCornerButton(String text, String tooltip, Runnable action) {
        collapseButton.setText(text);
        collapseButton.setTooltip(new Tooltip(tooltip));
        collapseButton.setOnAction(e -> action.run());
    }

    public BooleanProperty expandedProperty() {
        return expanded;
    }

    public boolean isExpanded() {
        return expanded.get();
    }

    public void setExpanded(boolean value) {
        expanded.set(value);
    }

    private void applyState() {
        boolean open = expanded.get();
        expandedView.setVisible(open);
        expandedView.setManaged(open);
        collapsedView.setVisible(!open);
        collapsedView.setManaged(!open);
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("collapsed"), !open);
    }
}
