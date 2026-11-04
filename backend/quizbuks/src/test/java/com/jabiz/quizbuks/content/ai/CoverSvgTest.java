package com.jabiz.quizbuks.content.ai;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The SVG of AI illustrations (docs/quizbuks/plans/Q96-ai-cover-illustration.md, D-Q96-5): what is drawn, what is
 * never drawn whatever the answer says, and the bounds.
 */
class CoverSvgTest {

    private static final String OPEN = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1200 630\">";

    private static BufferedImage draw(String svg) throws CoverSvg.Invalid {
        BufferedImage image = new BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1200, 630);
        CoverSvg.parse(svg).paint(g, 1200, 630);
        g.dispose();
        return image;
    }

    private static Color at(BufferedImage image, int x, int y) {
        return new Color(image.getRGB(x, y));
    }

    @Test
    void drawsShapesWithFillsStrokesTransformsAndStyles() throws CoverSvg.Invalid {
        BufferedImage image = draw(OPEN
            + "<rect width=\"1200\" height=\"630\" fill=\"#123456\"/>"
            + "<g transform=\"translate(100 100)\" fill=\"red\">"
            + "<circle cx=\"0\" cy=\"0\" r=\"50\"/>"
            + "<rect x=\"200\" y=\"-40\" width=\"80\" height=\"80\" style=\"fill: rgb(0, 255, 0)\"/>"
            + "</g>"
            + "<path d=\"M600 300 h100 v100 h-100 z\" fill=\"hsl(240, 100%, 50%)\"/>"
            + "<polygon points=\"900,100 1000,100 950,200\" fill=\"#ff0\"/>"
            + "<line x1=\"0\" y1=\"600\" x2=\"1200\" y2=\"600\" stroke=\"white\" stroke-width=\"10\"/>"
            + "</svg>");
        assertThat(at(image, 5, 5)).isEqualTo(new Color(0x123456));
        assertThat(at(image, 100, 100)).isEqualTo(Color.RED);
        assertThat(at(image, 340, 100)).isEqualTo(Color.GREEN);
        assertThat(at(image, 650, 350)).isEqualTo(Color.BLUE);
        assertThat(at(image, 950, 130)).isEqualTo(Color.YELLOW);
        assertThat(at(image, 600, 600)).isEqualTo(Color.WHITE);
    }

    @Test
    void drawsGradientsAndOpacity() throws CoverSvg.Invalid {
        BufferedImage image = draw(OPEN
            + "<defs><linearGradient id=\"sky\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">"
            + "<stop offset=\"0\" stop-color=\"#000000\"/><stop offset=\"100%\" stop-color=\"#ffffff\"/>"
            + "</linearGradient>"
            + "<radialGradient id=\"sun\" href=\"#sky\" cx=\"50%\" cy=\"50%\" r=\"50%\"/></defs>"
            + "<rect width=\"1200\" height=\"630\" fill=\"url(#sky)\"/>"
            + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\" fill=\"black\" opacity=\"0.5\"/>"
            + "<circle cx=\"1000\" cy=\"500\" r=\"100\" fill=\"url(#sun)\"/>"
            + "<rect x=\"500\" y=\"500\" width=\"10\" height=\"10\" fill=\"url(#missing) red\"/>"
            + "</svg>");
        // Dark on the left, light on the right.
        assertThat(at(image, 1150, 300).getRed()).isGreaterThan(at(image, 50, 300).getRed() + 150);
        // The radial gradient borrowed its stops: black in the middle.
        assertThat(at(image, 1000, 500).getRed()).isLessThan(40);
        // A missing gradient's fallback.
        assertThat(at(image, 505, 505)).isEqualTo(Color.RED);
    }

    @Test
    void pathsReadEveryCommandAndCompactNumbers() {
        Path2D path = new Path2D.Double();
        path.moveTo(0, 0);
        CoverSvg.arc(path, 0, 0, 50, 50, 0, false, true, 100, 0);
        // Half a circle of radius 50 through (50, -50) or (50, 50): its bounds are 100 wide and 50 high.
        assertThat(path.getBounds2D().getWidth()).isCloseTo(100, within(0.5));
        assertThat(path.getBounds2D().getHeight()).isCloseTo(50, within(0.5));

        CoverSvg.Numbers numbers = new CoverSvg.Numbers("10-5.5.5e2,1e");
        assertThat(numbers.number()).isEqualTo(10);
        assertThat(numbers.number()).isEqualTo(-5.5);
        assertThat(numbers.number()).isEqualTo(50);
        assertThat(numbers.number()).isEqualTo(1);
        // Arc flags written together.
        assertThat(new CoverSvg.Numbers("5 5 0 011 1").arc()).containsExactly(5, 5, 0, 0, 1, 1, 1);

        AffineTransform t = CoverSvg.transform("translate(10,20) rotate(90) scale(2)");
        assertThat(t.transform(new Point2D.Double(1, 0), null).distance(10, 22)).isLessThan(1e-9);
        assertThat(CoverSvg.transform("evil(1)")).isNull();
    }

    @Test
    void neverDrawsWhatIsNotOnTheList() throws CoverSvg.Invalid {
        // Scripts, text, images, foreign objects, use, filters and styles are skipped with all they hold.
        BufferedImage image = draw(OPEN
            + "<script>alert(1)</script>"
            + "<text x=\"0\" y=\"100\" font-size=\"200\" fill=\"red\">HELLO</text>"
            + "<image href=\"https://example.com/x.png\" width=\"1200\" height=\"630\"/>"
            + "<foreignObject width=\"1200\" height=\"630\"><rect width=\"1200\" height=\"630\" fill=\"red\"/>"
            + "</foreignObject>"
            + "<defs><rect id=\"r\" width=\"1200\" height=\"630\" fill=\"red\"/></defs>"
            + "<use href=\"#r\"/>"
            + "<style>rect { fill: red }</style>"
            + "<rect x=\"1100\" y=\"530\" width=\"100\" height=\"100\" fill=\"url(https://example.com/x)\" "
            + "onload=\"alert(1)\"/>"
            + "</svg>");
        assertThat(at(image, 600, 300)).isEqualTo(Color.WHITE);
        assertThat(at(image, 50, 50)).isEqualTo(Color.WHITE);
        // An outside paint is no paint (and the fallback none).
        assertThat(at(image, 1150, 580)).isEqualTo(Color.WHITE);
    }

    @Test
    void refusesWhatIsNotASafeBoundedSvg() {
        assertThatThrownBy(() -> CoverSvg.parse(null)).isInstanceOf(CoverSvg.Invalid.class);
        assertThatThrownBy(() -> CoverSvg.parse("<html/>")).hasMessageContaining("root");
        assertThatThrownBy(() -> CoverSvg.parse("not xml")).isInstanceOf(CoverSvg.Invalid.class);
        // No DTD, so no entities, internal or external.
        assertThatThrownBy(() -> CoverSvg.parse("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM "
            + "\"file:///etc/passwd\">]><svg viewBox=\"0 0 10 10\"><rect width=\"&x;\" height=\"1\"/></svg>"))
            .isInstanceOf(CoverSvg.Invalid.class);
        assertThatThrownBy(() -> CoverSvg.parse("<!DOCTYPE svg [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;\">]>"
            + "<svg viewBox=\"0 0 10 10\"/>")).isInstanceOf(CoverSvg.Invalid.class);
        // Bounds.
        assertThatThrownBy(() -> CoverSvg.parse(OPEN + "<rect width=\"1\" height=\"1\"/>".repeat(5_000) + "</svg>"))
            .isInstanceOf(CoverSvg.Invalid.class);
        assertThatThrownBy(() -> CoverSvg.parse(OPEN + "<g>".repeat(40) + "<rect width=\"1\" height=\"1\"/>"
            + "</g>".repeat(40) + "</svg>")).hasMessageContaining("deeper");
        assertThatThrownBy(() -> CoverSvg.parse(OPEN + "<rect width=\"1e9\" height=\"1\"/></svg>"))
            .hasMessageContaining("coordinate");
        assertThatThrownBy(() -> CoverSvg.parse(OPEN + "<path d=\"M0 0 L1e9 0\"/></svg>"))
            .hasMessageContaining("coordinate");
        assertThatThrownBy(() -> CoverSvg.parse("<svg viewBox=\"0 0 -1 5\"><rect width=\"1\" height=\"1\"/></svg>"))
            .hasMessageContaining("viewBox");
        // Nothing to draw.
        assertThatThrownBy(() -> CoverSvg.parse(OPEN + "<text>Hi</text></svg>")).hasMessageContaining("nothing");
    }

    @Test
    void anotherViewBoxIsScaledToCoverThePicture() throws CoverSvg.Invalid {
        BufferedImage image = draw("<svg viewBox=\"0 0 120 63\"><rect width=\"60\" height=\"63\" fill=\"black\"/>"
            + "</svg>");
        assertThat(at(image, 300, 300)).isEqualTo(Color.BLACK);
        assertThat(at(image, 900, 300)).isEqualTo(Color.WHITE);
    }

    @Test
    void theRendererDrawsAnIllustrationWithItsTitleOnABand() throws CoverSvg.Invalid, IOException {
        CoverRenderer renderer = CoverRenderer.withoutFallbacks();
        CoverSvg.Drawing white = CoverSvg.parse(OPEN + "<rect width=\"1200\" height=\"630\" fill=\"#ffffff\"/></svg>");
        BufferedImage plain = ImageIO.read(new ByteArrayInputStream(renderer.renderIllustration(white, null, null)));
        assertThat(plain.getWidth()).isEqualTo(1200);
        assertThat(plain.getHeight()).isEqualTo(630);
        assertThat(at(plain, 600, 600)).isEqualTo(Color.WHITE);
        // The band darkens the bottom and the text is white on it.
        BufferedImage titled = ImageIO.read(new ByteArrayInputStream(renderer.renderIllustration(white,
            "Rivers of Europe", "bottom")));
        assertThat(at(titled, 20, 620).getRed()).isLessThan(140);
        assertThat(at(titled, 20, 20)).isEqualTo(Color.WHITE);
        BufferedImage top = ImageIO.read(new ByteArrayInputStream(renderer.renderIllustration(white,
            "Rivers of Europe", "top")));
        assertThat(at(top, 20, 10).getRed()).isLessThan(140);
        assertThat(at(top, 20, 620)).isEqualTo(Color.WHITE);
        assertThatThrownBy(() -> renderer.renderIllustration(white, "͸", "top"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
