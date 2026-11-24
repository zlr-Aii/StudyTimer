package com.studytimer.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.List;

/**
 * 无系统标题栏（UNDECORATED）窗口的自绘标题栏：最小化/最大化/关闭按钮、拖动、双击最大化、边缘缩放。
 * 关闭按钮发出 WINDOW_CLOSE_REQUEST，沿用 Stage 上已有的关闭处理（隐藏到托盘）。
 */
public final class WindowChrome {

    private static final double RESIZE_MARGIN = 6;
    private static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    /** 拖动时鼠标离屏幕边缘多近算“拖到边缘”。 */
    private static final double SNAP_MARGIN = 4;

    private final Stage stage;
    private boolean maximized;
    /** 是否贴靠在半屏（snapSide：-1 左，1 右）。 */
    private boolean snapped;
    private int snapSide;
    private boolean winDown;
    private Rectangle2D restoreBounds;
    private Button maximizeButton;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;

    private int resizeEdges;
    private double startScreenX, startScreenY, startX, startY, startW, startH;

    public WindowChrome(Stage stage) {
        this.stage = stage;
    }

    public Node createControls() {
        Button minimize = windowButton("\uE921");
        minimize.setOnAction(e -> stage.setIconified(true));

        maximizeButton = windowButton("\uE922");
        maximizeButton.setOnAction(e -> toggleMaximize());

        Button close = windowButton("\uE8BB");
        close.getStyleClass().add("window-close-button");
        close.setOnAction(e -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));

        HBox box = new HBox(0, minimize, maximizeButton, close);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private static Button windowButton(String glyph) {
        Button button = new Button(glyph);
        button.getStyleClass().add("window-button");
        button.setFocusTraversable(false);
        return button;
    }

    /** 在标题栏空白处（及 Label 上）按住可拖动窗口，双击切换最大化。 */
    public void installDrag(Node titleBar) {
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            dragging = e.getButton() == MouseButton.PRIMARY && !isOnControl(e, titleBar);
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!dragging) {
                return;
            }
            if (maximized || snapped) {
                // 从最大化/贴靠状态拖出：恢复原尺寸，并让鼠标保持在标题栏上的相对位置
                double ratio = dragOffsetX / stage.getWidth();
                restore();
                dragOffsetX = stage.getWidth() * ratio;
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        titleBar.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (dragging && !maximized) {
                // 拖到屏幕边缘松开：上 = 最大化，左/右 = 占半屏
                Rectangle2D bounds = screenBoundsAt(e.getScreenX(), e.getScreenY());
                if (e.getScreenY() <= bounds.getMinY() + SNAP_MARGIN) {
                    maximize();
                } else if (e.getScreenX() <= bounds.getMinX() + SNAP_MARGIN) {
                    snap(-1, bounds);
                } else if (e.getScreenX() >= bounds.getMaxX() - SNAP_MARGIN) {
                    snap(1, bounds);
                } else if (stage.getY() < screenBounds().getMinY()) {
                    stage.setY(screenBounds().getMinY());
                }
            }
            dragging = false;
        });
        titleBar.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isOnControl(e, titleBar)) {
                toggleMaximize();
            }
        });
    }

    private static boolean isOnControl(MouseEvent e, Node titleBar) {
        Node node = e.getPickResult().getIntersectedNode();
        while (node != null && node != titleBar) {
            if (node instanceof Control && !(node instanceof Label)) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    /** 窗口四边/四角缩放。 */
    public void installResize(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            int edges = maximized ? 0 : edgesAt(scene, e.getSceneX(), e.getSceneY());
            scene.setCursor(cursorFor(edges));
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
            if (resizeEdges == 0) {
                scene.setCursor(null);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            resizeEdges = (maximized || e.getButton() != MouseButton.PRIMARY)
                    ? 0 : edgesAt(scene, e.getSceneX(), e.getSceneY());
            if (resizeEdges != 0) {
                startScreenX = e.getScreenX();
                startScreenY = e.getScreenY();
                startX = stage.getX();
                startY = stage.getY();
                startW = stage.getWidth();
                startH = stage.getHeight();
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (resizeEdges == 0) {
                return;
            }
            double dx = e.getScreenX() - startScreenX;
            double dy = e.getScreenY() - startScreenY;
            double minW = Math.max(stage.getMinWidth(), 200);
            double minH = Math.max(stage.getMinHeight(), 150);

            if ((resizeEdges & RIGHT) != 0) {
                stage.setWidth(Math.max(minW, startW + dx));
            }
            if ((resizeEdges & BOTTOM) != 0) {
                stage.setHeight(Math.max(minH, startH + dy));
            }
            if ((resizeEdges & LEFT) != 0) {
                double w = Math.max(minW, startW - dx);
                stage.setX(startX + startW - w);
                stage.setWidth(w);
            }
            if ((resizeEdges & TOP) != 0) {
                double h = Math.max(minH, startH - dy);
                stage.setY(startY + startH - h);
                stage.setHeight(h);
            }
            e.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizeEdges != 0) {
                resizeEdges = 0;
                e.consume();
            }
        });
    }

    private static int edgesAt(Scene scene, double x, double y) {
        int edges = 0;
        if (x < RESIZE_MARGIN) edges |= LEFT;
        if (x > scene.getWidth() - RESIZE_MARGIN) edges |= RIGHT;
        if (y < RESIZE_MARGIN) edges |= TOP;
        if (y > scene.getHeight() - RESIZE_MARGIN) edges |= BOTTOM;
        return edges;
    }

    private static Cursor cursorFor(int edges) {
        return switch (edges) {
            case LEFT, RIGHT -> Cursor.H_RESIZE;
            case TOP, BOTTOM -> Cursor.V_RESIZE;
            case LEFT | TOP -> Cursor.NW_RESIZE;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE;
            case RIGHT | TOP -> Cursor.NE_RESIZE;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE;
            default -> null;
        };
    }

    public void toggleMaximize() {
        if (maximized) {
            restore();
        } else {
            maximize();
        }
    }

    /** 贴靠到屏幕左半（side = -1）或右半（side = 1）。 */
    private void snap(int side, Rectangle2D bounds) {
        rememberRestoreBounds();
        double half = Math.max(Math.floor(bounds.getWidth() / 2), stage.getMinWidth());
        stage.setX(side < 0 ? bounds.getMinX() : bounds.getMaxX() - half);
        stage.setY(bounds.getMinY());
        stage.setWidth(half);
        stage.setHeight(bounds.getHeight());
        snapped = true;
        snapSide = side;
    }

    /**
     * Win + 方向键（系统不为无边框窗口处理时由这里处理）：
     * 上 = 最大化，下 = 还原/最小化，左/右 = 半屏贴靠，再按一次移到相邻屏幕。
     */
    public void installKeyboardSnap(Scene scene) {
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.WINDOWS) {
                winDown = true;
                return;
            }
            if (!winDown && !e.isMetaDown()) {
                return;
            }
            switch (e.getCode()) {
                case UP -> {
                    if (!maximized) {
                        maximize();
                    }
                }
                case DOWN -> {
                    if (maximized || snapped) {
                        restore();
                    } else {
                        stage.setIconified(true);
                    }
                }
                case LEFT -> keyboardSnap(-1);
                case RIGHT -> keyboardSnap(1);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.WINDOWS) {
                winDown = false;
            }
        });
        stage.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) {
                winDown = false;
            }
        });
    }

    private void keyboardSnap(int side) {
        Rectangle2D bounds = screenBounds();
        if (maximized) {
            setMaximizedState(false);
        }
        if (snapped && snapSide == side) {
            // 已贴在这一侧：移到该方向的相邻屏幕的另一侧
            Rectangle2D next = neighbourScreen(bounds, side);
            if (next != null) {
                snap(-side, next);
            }
            return;
        }
        if (snapped && snapSide == -side) {
            restore();
            return;
        }
        snap(side, bounds);
    }

    private static Rectangle2D neighbourScreen(Rectangle2D current, int side) {
        Rectangle2D best = null;
        for (Screen screen : Screen.getScreens()) {
            Rectangle2D b = screen.getVisualBounds();
            boolean beyond = side < 0 ? b.getMaxX() <= current.getMinX() : b.getMinX() >= current.getMaxX();
            if (beyond && (best == null
                    || Math.abs(b.getMinX() - current.getMinX()) < Math.abs(best.getMinX() - current.getMinX()))) {
                best = b;
            }
        }
        return best;
    }

    private void rememberRestoreBounds() {
        if (!maximized && !snapped) {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        }
    }

    private static Rectangle2D screenBoundsAt(double x, double y) {
        List<Screen> screens = Screen.getScreensForRectangle(x, y, 1, 1);
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
    }

    /** 手动最大化到当前屏幕的可用区域（不遮住任务栏）。 */
    private void maximize() {
        rememberRestoreBounds();
        snapped = false;
        Rectangle2D bounds = screenBounds();
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        setMaximizedState(true);
    }

    private void restore() {
        if (restoreBounds != null) {
            stage.setWidth(restoreBounds.getWidth());
            stage.setHeight(restoreBounds.getHeight());
            // 还原到当前所在屏幕内（键盘移屏后不跳回原屏幕）
            Rectangle2D bounds = screenBounds();
            Rectangle2D old = restoreBounds;
            boolean onScreen = bounds.contains(old.getMinX() + old.getWidth() / 2, old.getMinY() + 10);
            stage.setX(onScreen ? old.getMinX() : bounds.getMinX() + (bounds.getWidth() - old.getWidth()) / 2);
            stage.setY(onScreen ? old.getMinY() : bounds.getMinY() + (bounds.getHeight() - old.getHeight()) / 2);
        }
        snapped = false;
        setMaximizedState(false);
    }

    private void setMaximizedState(boolean value) {
        maximized = value;
        if (maximizeButton != null) {
            maximizeButton.setText(value ? "\uE923" : "\uE922");
        }
        Parent root = stage.getScene() == null ? null : stage.getScene().getRoot();
        if (root != null) {
            root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("maximized"), value);
        }
    }

    private Rectangle2D screenBounds() {
        double cx = stage.getX() + stage.getWidth() / 2;
        double cy = stage.getY() + stage.getHeight() / 2;
        List<Screen> screens = Screen.getScreensForRectangle(cx, cy, 1, 1);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }
}
