import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the launcher icon (192x192) and the Fire TV / Android TV banner (320x180) into the given folder. */
class MakeArt {
    static final Color BG1 = new Color(0x0B1220), BG2 = new Color(0x1B2A4A), ACCENT = new Color(0x38BDF8), WARM = new Color(0xF472B6);

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        ImageIO.write(icon(192), "png", new File(dir, "icon.png"));
        ImageIO.write(banner(), "png", new File(dir, "banner.png"));
        System.out.println("MakeArt: icon.png and banner.png in " + dir);
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** A globe inside a TV screen. */
    static void globe(Graphics2D g, double cx, double cy, double r) {
        g.setPaint(new GradientPaint((float) (cx - r), (float) (cy - r), ACCENT, (float) (cx + r), (float) (cy + r), WARM));
        g.setStroke(new BasicStroke((float) (r * 0.13), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke((float) (r * 0.09), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(cx - r * 0.45, cy - r, r * 0.9, 2 * r));
        g.draw(new Line2D.Double(cx - r, cy, cx + r, cy));
        g.draw(new Arc2D.Double(cx - r * 0.92, cy - r * 1.35, r * 1.84, r * 1.1, 200, 140, Arc2D.OPEN));
        g.draw(new Arc2D.Double(cx - r * 0.92, cy + r * 0.25, r * 1.84, r * 1.1, 20, 140, Arc2D.OPEN));
    }

    static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, BG2, s, s, BG1));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.36, s * 0.36));
        globe(g, s / 2.0, s / 2.0, s * 0.3);
        g.dispose();
        return img;
    }

    static BufferedImage banner() {
        int w = 320, h = 180;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, BG2, w, h, BG1));
        g.fillRect(0, 0, w, h);
        globe(g, 66, 90, 34);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString("Channel", 116, 84);
        g.drawString("Atlas", 116, 120);
        g.setColor(new Color(0x94A3B8));
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.drawString("Free live TV worldwide", 117, 145);
        g.dispose();
        return img;
    }
}
