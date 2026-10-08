package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Components.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bar above the page (Swing: the page is a native Chromium view that JavaFX cannot host). Two rows on a
 * 720 px wide screen: navigation (app home, page back, reload), then the status: a coloured title, one sentence
 * that wraps (never truncated) and the actions for the current situation. Touch targets are 80 px tall.
 * Colours follow the app theme ({@link Theme}), fonts are the app's Geist.
 */
public final class BrowserBar extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(BrowserBar.class);
    private static final int TOUCH = 80;
    private static final int GAP = 12;
    private static final int PAD = 16;

    /** Colours of the bar (from the app's theme palette). */
    public record Theme(Color bg, Color surface, Color surface2, Color line, Color text, Color text2, Color accent,
                        Color onAccent, Color ok, Color warn, Color danger) {

        public static final Theme DARK = new Theme(hex("#0C0C0E"), hex("#151518"), hex("#1D1D21"), hex("#2C2C32"),
                hex("#F4F4F5"), hex("#ABABB4"), hex("#4F9DFF"), hex("#06121F"), hex("#3DD68C"), hex("#F5B83D"),
                hex("#FF5C5F"));
        public static final Theme LIGHT = new Theme(hex("#F3F2EF"), hex("#FFFFFF"), hex("#ECEAE6"), hex("#DEDBD5"),
                hex("#141416"), hex("#55555C"), hex("#1A6BE0"), hex("#FFFFFF"), hex("#1E8E52"), hex("#A86500"),
                hex("#C9302C"));

        static Color hex(String s) {
            return Color.decode(s);
        }

        Color tone(BrowserStatus.Tone tone) {
            return switch (tone) {
                case SUCCESS -> ok;
                case WARNING -> warn;
                case ERROR -> danger;
                case INFO, PROGRESS -> accent;
            };
        }
    }

    /** Fixed buttons of the navigation row. */
    public enum Nav {
        HOME, PAGE_BACK, RELOAD
    }

    private final Consumer<Nav> onNav;
    private final Consumer<BrowserStatus.Action> onAction;
    private Theme theme = Theme.DARK;
    private final Fonts fonts = Fonts.load();

    private final Map<Nav, PillButton> navButtons = new EnumMap<>(Nav.class);
    private final WrappingText title = new WrappingText();
    private final WrappingText detail = new WrappingText();
    private final ToneDot dot = new ToneDot();
    private final JPanel actions = new JPanel();
    private final ProgressStrip progress = new ProgressStrip();
    private final JPanel statusRow;
    private BrowserStatus status;

    public BrowserBar(Consumer<Nav> onNav, Consumer<BrowserStatus.Action> onAction) {
        this.onNav = onNav;
        this.onAction = onAction;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(PAD, PAD, 0, PAD));

        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.X_AXIS));
        PillButton home = new PillButton(I18n.t("browser.action.back_home"), Icon.HOME, false);
        PillButton back = new PillButton(I18n.t("browser.action.page_back"), Icon.BACK, false);
        PillButton reload = new PillButton(I18n.t("browser.action.reload"), Icon.RELOAD, false);
        navButtons.put(Nav.HOME, home);
        navButtons.put(Nav.PAGE_BACK, back);
        navButtons.put(Nav.RELOAD, reload);
        home.addActionListener(e -> onNav.accept(Nav.HOME));
        back.addActionListener(e -> onNav.accept(Nav.PAGE_BACK));
        reload.addActionListener(e -> onNav.accept(Nav.RELOAD));
        nav.add(home);
        nav.add(Box.createHorizontalStrut(GAP));
        nav.add(back);
        nav.add(Box.createHorizontalGlue());
        nav.add(reload);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BorderLayout(GAP, 4));
        JPanel titleRow = new JPanel(new BorderLayout(GAP, 0));
        titleRow.setOpaque(false);
        titleRow.add(dot, BorderLayout.WEST);
        titleRow.add(title, BorderLayout.CENTER);
        text.add(titleRow, BorderLayout.NORTH);
        text.add(detail, BorderLayout.CENTER);

        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));

        statusRow = new JPanel(new BorderLayout(GAP, GAP));
        statusRow.setOpaque(false);
        statusRow.setBorder(BorderFactory.createEmptyBorder(GAP, 0, PAD, 0));
        statusRow.add(text, BorderLayout.CENTER);
        statusRow.add(actions, BorderLayout.SOUTH);

        add(nav, BorderLayout.NORTH);
        add(statusRow, BorderLayout.CENTER);
        add(progress, BorderLayout.SOUTH);

        title.setFont(fonts.title);
        detail.setFont(fonts.body);
        applyTheme(theme);
    }

    public void applyTheme(Theme t) {
        this.theme = t;
        setBackground(t.surface());
        title.setForeground(t.text());
        detail.setForeground(t.text2());
        navButtons.values().forEach(b -> b.setColors(t.surface2(), t.text(), t.line()));
        progress.color = t.accent();
        progress.track = t.line();
        if (status != null) {
            show(status);
        }
        repaint();
    }

    /** Shows a status (call on the Swing thread). */
    public void show(BrowserStatus s) {
        this.status = s;
        title.setText(s.title());
        detail.setText(s.detail());
        dot.color = theme.tone(s.tone());
        dot.repaint();
        actions.removeAll();
        if (s.actions().stream().filter(a -> a != BrowserStatus.Action.BACK_HOME && a != BrowserStatus.Action.RELOAD)
                .count() != 1) {
            actions.add(Box.createHorizontalGlue());
        }
        List<BrowserStatus.Action> list = new ArrayList<>(s.actions());
        list.remove(BrowserStatus.Action.BACK_HOME); // always on the navigation row
        list.remove(BrowserStatus.Action.RELOAD);
        for (int i = 0; i < list.size(); i++) {
            BrowserStatus.Action a = list.get(i);
            boolean primary = i == 0;
            PillButton b = new PillButton(a.label(), null, primary);
            if (primary) {
                b.setColors(a == BrowserStatus.Action.SYNC_STOP ? theme.surface2() : theme.accent(),
                        a == BrowserStatus.Action.SYNC_STOP ? theme.text() : theme.onAccent(), null);
            } else {
                b.setColors(theme.surface2(), theme.text(), theme.line());
            }
            b.addActionListener(e -> onAction.accept(a));
            if (i > 0) {
                actions.add(Box.createHorizontalStrut(GAP));
            }
            actions.add(b);
        }
        actions.setVisible(!list.isEmpty());
        // one button: beside the text (the page keeps more room); more: on their own row
        statusRow.remove(actions);
        statusRow.add(actions, list.size() == 1 ? BorderLayout.EAST : BorderLayout.SOUTH);
        boolean errorReload = s.actions().contains(BrowserStatus.Action.RELOAD);
        PillButton reload = navButtons.get(Nav.RELOAD);
        reload.setColors(errorReload ? theme.accent() : theme.surface2(), errorReload ? theme.onAccent() : theme.text(),
                errorReload ? null : theme.line());
        progress.setMode(s.hasProgress() ? s.progress() : BrowserStatus.NO_PROGRESS);
        revalidate();
        repaint();
    }

    public BrowserStatus shown() {
        return status;
    }

    /** Enables the "page back" button when the page has a history. */
    public void setCanGoBack(boolean canGoBack) {
        navButtons.get(Nav.PAGE_BACK).setEnabled(canGoBack);
    }

    // ------------------------------------------------------------------ components

    /** Text that wraps on words to the available width and grows in height: never truncated. */
    static final class WrappingText extends JComponent {
        private String text = "";
        private int laidOutWidth = -1;

        WrappingText() {
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (getWidth() != laidOutWidth) {
                        laidOutWidth = getWidth();
                        revalidate(); // the height depends on the width
                    }
                }
            });
        }

        void setText(String t) {
            String next = t == null ? "" : t;
            if (!next.equals(text)) {
                text = next;
                revalidate();
                repaint();
            }
        }

        String text() {
            return text;
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : (getParent() != null ? getParent().getWidth() : 0);
            if (width <= 0) {
                width = 600;
            }
            FontMetrics fm = getFontMetrics(getFont());
            int lines = text.isEmpty() ? 0 : lines(text, fm, width).size();
            return new Dimension(width, lines * fm.getHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            g2.setColor(getForeground());
            FontMetrics fm = g2.getFontMetrics();
            int y = fm.getAscent();
            for (String line : lines(text, fm, getWidth())) {
                g2.drawString(line, 0, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }

        /** Greedy word wrap; a word longer than the line is split. */
        static List<String> lines(String text, FontMetrics fm, int width) {
            List<String> out = new ArrayList<>();
            for (String paragraph : text.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.length() == 0 ? word : line + " " + word;
                    if (fm.stringWidth(candidate) <= width || line.length() == 0 && fm.stringWidth(word) <= width) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    String rest = word;
                    while (fm.stringWidth(rest) > width && rest.length() > 1) {
                        int cut = rest.length() - 1;
                        while (cut > 1 && fm.stringWidth(rest.substring(0, cut)) > width) {
                            cut--;
                        }
                        out.add(rest.substring(0, cut));
                        rest = rest.substring(cut);
                    }
                    line.append(rest);
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                }
            }
            return out;
        }
    }

    /** Small coloured dot before the title (status tone). */
    static final class ToneDot extends JComponent {
        Color color = Color.GRAY;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(14, 14);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int y = Math.max(0, (getParent() != null ? Math.min(getHeight(), 36) : getHeight()) / 2 - 7);
            g2.fillOval(0, y, 14, 14);
            g2.dispose();
        }
    }

    /** Thin progress line at the bottom of the bar; animated only while an activity of unknown length runs. */
    static final class ProgressStrip extends JComponent {
        Color color = Color.BLUE;
        Color track = Color.DARK_GRAY;
        private double value = BrowserStatus.NO_PROGRESS;
        private int phase;
        private final Timer timer = new Timer(50, e -> {
            phase = (phase + 4) % 100;
            repaint();
        });

        void setMode(double progress) {
            value = progress;
            if (progress == -1) {
                timer.start();
            } else {
                timer.stop();
            }
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, 4);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (value == BrowserStatus.NO_PROGRESS) {
                return;
            }
            int w = getWidth();
            g.setColor(track);
            g.fillRect(0, 0, w, getHeight());
            g.setColor(color);
            if (value < 0) {
                int seg = w / 4;
                g.fillRect(phase * (w + seg) / 100 - seg, 0, seg, getHeight());
            } else {
                g.fillRect(0, 0, (int) Math.round(w * Math.min(1, value)), getHeight());
            }
        }
    }

    enum Icon {
        HOME, BACK, RELOAD
    }

    /** Rounded touch button (80 px tall, radius 24) with an optional drawn icon. */
    final class PillButton extends JButton {
        private final Icon icon;
        private Color fill = Color.DARK_GRAY;
        private Color ink = Color.WHITE;
        private Color stroke;

        PillButton(String label, Icon icon, boolean primary) {
            super(label);
            this.icon = icon;
            setFont(fonts.button);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(false);
            setMargin(new Insets(0, 0, 0, 0));
        }

        void setColors(Color fill, Color ink, Color stroke) {
            this.fill = fill;
            this.ink = ink;
            this.stroke = stroke;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(getText()) + 2 * 24 + (icon != null ? 28 + 10 : 0);
            return new Dimension(Math.max(TOUCH, w), TOUCH);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            float alpha = isEnabled() ? 1f : 0.4f;
            g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha));
            RoundRectangle2D shape = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, 48, 48);
            g2.setColor(getModel().isPressed() ? fill.darker() : fill);
            g2.fill(shape);
            if (stroke != null) {
                g2.setColor(stroke);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(shape);
            }
            g2.setColor(ink);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(getText());
            int iconW = icon != null ? 28 + 10 : 0;
            int x = (getWidth() - textW - iconW) / 2;
            int cy = getHeight() / 2;
            if (icon != null) {
                drawIcon(g2, icon, x, cy);
                x += iconW;
            }
            g2.drawString(getText(), x, cy + (fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }

        private void drawIcon(Graphics2D g2, Icon which, int x, int cy) {
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D p = new Path2D.Double();
            switch (which) {
                case HOME -> {
                    p.moveTo(x + 2, cy + 1);
                    p.lineTo(x + 14, cy - 11);
                    p.lineTo(x + 26, cy + 1);
                    p.moveTo(x + 6, cy - 3);
                    p.lineTo(x + 6, cy + 12);
                    p.lineTo(x + 22, cy + 12);
                    p.lineTo(x + 22, cy - 3);
                    g2.draw(p);
                }
                case BACK -> {
                    p.moveTo(x + 18, cy - 11);
                    p.lineTo(x + 7, cy);
                    p.lineTo(x + 18, cy + 11);
                    g2.draw(p);
                }
                case RELOAD -> {
                    g2.draw(new Arc2D.Double(x + 3, cy - 11, 22, 22, 60, 290, Arc2D.OPEN));
                    p.moveTo(x + 25, cy - 13);
                    p.lineTo(x + 25, cy - 4);
                    p.lineTo(x + 16, cy - 4);
                    g2.draw(p);
                }
            }
        }
    }

    /** Geist from the app's resources, with a system fallback. */
    record Fonts(Font title, Font body, Font button) {
        private static Fonts cached;

        static synchronized Fonts load() {
            if (cached == null) {
                Font semi = font("/Font/Geist/Geist-SemiBold.ttf", Font.BOLD);
                Font regular = font("/Font/Geist/Geist-Regular.ttf", Font.PLAIN);
                Font medium = font("/Font/Geist/Geist-Medium.ttf", Font.PLAIN);
                cached = new Fonts(semi.deriveFont(26f), regular.deriveFont(22f), medium.deriveFont(22f));
            }
            return cached;
        }

        private static Font font(String resource, int fallbackStyle) {
            try (InputStream in = BrowserBar.class.getResourceAsStream(resource)) {
                if (in != null) {
                    Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                    return f;
                }
            } catch (Exception e) {
                log.debug("Font {} not loaded: {}", resource, e.toString());
            }
            return new Font(Font.SANS_SERIF, fallbackStyle, 22);
        }
    }

    /** Width a text needs with the given font (tests check that labels fit). */
    static double textWidth(Font font, String text) {
        return font.getStringBounds(text, new FontRenderContext(null, true, true)).getWidth();
    }

    static void onSwing(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
