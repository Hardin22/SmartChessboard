/*
 * javaChess logo generator. Reproducible: run from the repository root with
 *
 *     java scripts/logo/LogoGenerator.java
 *
 * The mark: a rounded tile holding a 2x2 board; the two light squares sit on the anti-diagonal and the
 * remaining dark square carries a dot, the LED of the smart board. Geometry on a 64-unit grid (same as
 * io.github.hardin22.javachess.Components.Logo). The wordmark is "javaChess" in Geist SemiBold, converted to
 * outlines so the SVGs do not depend on installed fonts.
 *
 * Outputs
 *   src/main/resources/images/logo/icon-{16..1024}.png   window / app icon (dark tile)
 *   docs/logo/mark-{dark,light}.svg                     mark for dark / light backgrounds
 *   docs/logo/javachess-logo-{dark,light}.svg/.png      mark + wordmark (README header)
 *   docs/logo/favicon.ico, favicon-{16,32}.png           favicon
 */

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LogoGenerator {

    record Palette(String tile, String cut, String led, String text) {
    }

    // Mark drawn on a dark page (white tile) / on a light page (black tile).
    static final Palette ON_DARK = new Palette("#EDEDED", "#0A0A0A", "#52A8FF", "#EDEDED");
    static final Palette ON_LIGHT = new Palette("#171717", "#FFFFFF", "#0068D6", "#171717");
    // App icon: black tile, white squares, blue LED.
    static final Palette ICON = new Palette("#0A0A0A", "#FAFAFA", "#3291FF", "#FAFAFA");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".");
        Path icons = root.resolve("src/main/resources/images/logo");
        Path docs = root.resolve("docs/logo");
        Files.createDirectories(icons);
        Files.createDirectories(docs);

        for (int size : new int[] { 16, 32, 64, 128, 256, 512, 1024 }) {
            ImageIO.write(renderMark(size, ICON), "png", icons.resolve("icon-" + size + ".png").toFile());
        }
        Files.writeString(docs.resolve("mark-dark.svg"), markSvg(ON_DARK));
        Files.writeString(docs.resolve("mark-light.svg"), markSvg(ON_LIGHT));

        Font geist = loadFont(root.resolve("src/main/resources/Font/Geist/Geist-SemiBold.ttf"));
        Files.writeString(docs.resolve("javachess-logo-dark.svg"), lockupSvg(ON_DARK, geist));
        Files.writeString(docs.resolve("javachess-logo-light.svg"), lockupSvg(ON_LIGHT, geist));
        ImageIO.write(renderLockup(ON_DARK, geist, 4), "png", docs.resolve("javachess-logo-dark.png").toFile());
        ImageIO.write(renderLockup(ON_LIGHT, geist, 4), "png", docs.resolve("javachess-logo-light.png").toFile());

        BufferedImage fav16 = renderMark(16, ICON);
        BufferedImage fav32 = renderMark(32, ICON);
        ImageIO.write(fav16, "png", docs.resolve("favicon-16.png").toFile());
        ImageIO.write(fav32, "png", docs.resolve("favicon-32.png").toFile());
        Files.write(docs.resolve("favicon.ico"), ico(List.of(fav16, fav32)));
        System.out.println("Logo written to " + icons + " and " + docs);
    }

    // ------------------------------------------------------------------ geometry (64-unit grid)

    static List<Shape> cuts() {
        return List.of(new RoundRectangle2D.Double(14, 32, 18, 18, 3, 3),
                new RoundRectangle2D.Double(32, 14, 18, 18, 3, 3));
    }

    static Shape tile() {
        return new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28);
    }

    static Shape led() {
        return new Ellipse2D.Double(23 - 4.5, 23 - 4.5, 9, 9);
    }

    // ------------------------------------------------------------------ raster

    static BufferedImage renderMark(int size, Palette p) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.scale(size / 64.0, size / 64.0);
        paintMark(g, p);
        g.dispose();
        return img;
    }

    static void paintMark(Graphics2D g, Palette p) {
        g.setColor(Color.decode(p.tile()));
        g.fill(tile());
        g.setColor(Color.decode(p.cut()));
        for (Shape s : cuts()) {
            g.fill(s);
        }
        g.setColor(Color.decode(p.led()));
        g.fill(led());
    }

    static BufferedImage renderLockup(Palette p, Font font, double scale) {
        Shape text = wordmark(font);
        Rectangle2D b = text.getBounds2D();
        double width = 64 + 16 + b.getMaxX() + 2;
        BufferedImage img = new BufferedImage((int) Math.ceil(width * scale), (int) Math.ceil(64 * scale),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        g.scale(scale, scale);
        paintMark(g, p);
        g.translate(64 + 16, 0);
        g.setColor(Color.decode(p.text()));
        g.fill(text);
        g.dispose();
        return img;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setStroke(new BasicStroke(1));
    }

    // ------------------------------------------------------------------ wordmark

    static Font loadFont(Path file) throws Exception {
        try (InputStream in = new FileInputStream(file.toFile())) {
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(40f);
        }
    }

    /** "javaChess" outlines, baseline placed so the x-height is centred on the 64-unit tile. */
    static Shape wordmark(Font font) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = font.createGlyphVector(frc, "javaChess");
        Shape outline = gv.getOutline();
        Rectangle2D cap = font.createGlyphVector(frc, "C").getOutline().getBounds2D();
        double baseline = 32 + cap.getHeight() / 2;
        return AffineTransform.getTranslateInstance(-outline.getBounds2D().getMinX(), baseline)
                .createTransformedShape(outline);
    }

    // ------------------------------------------------------------------ svg

    static String markSvg(Palette p) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" width=\"64\" height=\"64\">\n"
                + markElements(p) + "</svg>\n";
    }

    static String markElements(Palette p) {
        return "  <rect width=\"64\" height=\"64\" rx=\"14\" fill=\"" + p.tile() + "\"/>\n"
                + "  <rect x=\"14\" y=\"32\" width=\"18\" height=\"18\" rx=\"1.5\" fill=\"" + p.cut() + "\"/>\n"
                + "  <rect x=\"32\" y=\"14\" width=\"18\" height=\"18\" rx=\"1.5\" fill=\"" + p.cut() + "\"/>\n"
                + "  <circle cx=\"23\" cy=\"23\" r=\"4.5\" fill=\"" + p.led() + "\"/>\n";
    }

    static String lockupSvg(Palette p, Font font) {
        Shape text = wordmark(font);
        double width = 64 + 16 + text.getBounds2D().getMaxX() + 2;
        String w = fmt(width);
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " 64\" width=\"" + w
                + "\" height=\"64\" role=\"img\" aria-label=\"javaChess\">\n" + markElements(p)
                + "  <path transform=\"translate(80 0)\" fill=\"" + p.text() + "\" d=\"" + pathData(text) + "\"/>\n"
                + "</svg>\n";
    }

    static String pathData(Shape shape) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> sb.append('M').append(fmt(c[0])).append(' ').append(fmt(c[1]));
                case PathIterator.SEG_LINETO -> sb.append('L').append(fmt(c[0])).append(' ').append(fmt(c[1]));
                case PathIterator.SEG_QUADTO -> sb.append('Q').append(fmt(c[0])).append(' ').append(fmt(c[1]))
                        .append(' ').append(fmt(c[2])).append(' ').append(fmt(c[3]));
                case PathIterator.SEG_CUBICTO -> sb.append('C').append(fmt(c[0])).append(' ').append(fmt(c[1]))
                        .append(' ').append(fmt(c[2])).append(' ').append(fmt(c[3])).append(' ').append(fmt(c[4]))
                        .append(' ').append(fmt(c[5]));
                case PathIterator.SEG_CLOSE -> sb.append('Z');
                default -> { }
            }
        }
        return sb.toString();
    }

    static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.equals("-0") ? "0" : s;
    }

    // ------------------------------------------------------------------ ico (PNG entries)

    static byte[] ico(List<BufferedImage> images) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (BufferedImage img : images) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            pngs.add(out.toByteArray());
        }
        int headerSize = 6 + 16 * images.size();
        int total = headerSize + pngs.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = headerSize;
        for (int i = 0; i < images.size(); i++) {
            BufferedImage img = images.get(i);
            buf.put((byte) (img.getWidth() >= 256 ? 0 : img.getWidth()));
            buf.put((byte) (img.getHeight() >= 256 ? 0 : img.getHeight()));
            buf.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            buf.put(png);
        }
        return buf.array();
    }
}
