import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生成 StudyTimer 图标：书法式“と”，短笔像枝芽，主笔像向右上扬起的枝条。
 * 用法：java packaging/IconGen.java   （在项目根目录运行）
 * 输出：packaging/icon.svg、packaging/StudyTimer.ico、src/main/resources/com/studytimer/ui/icon-*.png
 */
public class IconGen {

    static final Color GREEN = new Color(0x16, 0xA3, 0x4A);

    /** 坐标系 0..100。每笔：三次贝塞尔段 + 宽度曲线（按 t 线性插值）。 */
    record Stroke(double[][] beziers, double[][] widths) {}

    static Stroke bud() {
        return new Stroke(
                new double[][]{{36, 12, 41, 22, 44, 32, 48, 44}},
                new double[][]{{0, 1}, {0.06, 5.5}, {0.18, 8.5}, {0.45, 7.5}, {1, 0.6}});
    }

    static Stroke main() {
        return new Stroke(
                new double[][]{
                        {74, 27, 58, 37, 36, 48, 31, 63},
                        {31, 63, 27, 79, 47, 89, 86, 76}},
                new double[][]{{0, 1}, {0.03, 5}, {0.1, 8}, {0.35, 12}, {0.55, 10.5},
                        {0.75, 7.5}, {0.92, 3}, {1, 0.4}});
    }

    static double width(double[][] curve, double t) {
        for (int i = 1; i < curve.length; i++) {
            if (t <= curve[i][0]) {
                double k = (t - curve[i - 1][0]) / (curve[i][0] - curve[i - 1][0]);
                return curve[i - 1][1] + k * (curve[i][1] - curve[i - 1][1]);
            }
        }
        return curve[curve.length - 1][1];
    }

    static double[] point(double[] b, double t) {
        double u = 1 - t;
        return new double[]{
                u * u * u * b[0] + 3 * u * u * t * b[2] + 3 * u * t * t * b[4] + t * t * t * b[6],
                u * u * u * b[1] + 3 * u * u * t * b[3] + 3 * u * t * t * b[5] + t * t * t * b[7]};
    }

    /** 中心线两侧按宽度偏移，得到闭合轮廓。scale 用于托盘小图加粗。 */
    static List<double[]> outline(Stroke s, double scale) {
        List<double[]> center = new ArrayList<>();
        int perSeg = 80;
        for (int seg = 0; seg < s.beziers().length; seg++) {
            for (int i = (seg == 0 ? 0 : 1); i <= perSeg; i++) {
                center.add(point(s.beziers()[seg], i / (double) perSeg));
            }
        }
        int n = center.size();
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] a = center.get(Math.max(0, i - 1));
            double[] b = center.get(Math.min(n - 1, i + 1));
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len = Math.hypot(dx, dy);
            double nx = -dy / len, ny = dx / len;
            double w = width(s.widths(), i / (double) (n - 1)) * scale / 2;
            double[] c = center.get(i);
            left.add(new double[]{c[0] + nx * w, c[1] + ny * w});
            right.add(new double[]{c[0] - nx * w, c[1] - ny * w});
        }
        List<double[]> result = new ArrayList<>(left);
        for (int i = right.size() - 1; i >= 0; i--) {
            result.add(right.get(i));
        }
        return result;
    }

    static Path2D shape(List<double[]> pts, double size) {
        Path2D path = new Path2D.Double();
        double k = size / 100.0;
        path.moveTo(pts.get(0)[0] * k, pts.get(0)[1] * k);
        for (int i = 1; i < pts.size(); i++) {
            path.lineTo(pts.get(i)[0] * k, pts.get(i)[1] * k);
        }
        path.closePath();
        return path;
    }

    static List<List<double[]>> glyph(boolean bold) {
        double scale = bold ? 1.55 : 1.0;
        return List.of(outline(bud(), scale), outline(main(), scale));
    }

    static BufferedImage render(int size, boolean bold) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(GREEN);
        for (List<double[]> pts : glyph(bold)) {
            g.fill(shape(pts, size));
        }
        g.dispose();
        return image;
    }

    static String svg() {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">\n");
        for (List<double[]> pts : glyph(false)) {
            sb.append("  <path fill=\"#16A34A\" d=\"M");
            for (int i = 0; i < pts.size(); i++) {
                sb.append(i == 0 ? "" : " L").append(String.format(Locale.ROOT, "%.2f %.2f", pts.get(i)[0], pts.get(i)[1]));
            }
            sb.append(" Z\"/>\n");
        }
        return sb.append("</svg>\n").toString();
    }

    /** PNG 内嵌式 ICO（Windows Vista 起支持）。 */
    static byte[] ico(int[] sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(render(size, size <= 32), "png", out);
            pngs.add(out.toByteArray());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(le16(0)); out.write(le16(1)); out.write(le16(sizes.length));
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(0); out.writeByte(0);
            out.write(le16(1)); out.write(le16(32));
            out.write(le32(pngs.get(i).length)); out.write(le32(offset));
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            out.write(png);
        }
        return bytes.toByteArray();
    }

    static byte[] le16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }
    static byte[] le32(int v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }

    public static void main(String[] args) throws IOException {
        Path pkg = Path.of("packaging");
        Path res = Path.of("src/main/resources/com/studytimer/ui");
        Files.createDirectories(res);
        Files.writeString(pkg.resolve("icon.svg"), svg());
        Files.write(pkg.resolve("StudyTimer.ico"), ico(new int[]{16, 24, 32, 48, 256}));
        ImageIO.write(render(512, false), "png", pkg.resolve("icon-preview.png").toFile());
        for (int size : new int[]{16, 32, 48, 256}) {
            ImageIO.write(render(size, size <= 32), "png", res.resolve("icon-" + size + ".png").toFile());
        }
        System.out.println("icons written");
    }
}
