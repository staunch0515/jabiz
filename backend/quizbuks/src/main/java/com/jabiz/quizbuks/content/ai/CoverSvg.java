package com.jabiz.quizbuks.content.ai;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The illustrations of AI covers (docs/quizbuks/plans/Q96-ai-cover-illustration.md, D-Q96-5): an SVG Claude wrote,
 * read and drawn here with Java2D. No SVG library sees it: the parser refuses a DTD (so no entities, nothing external),
 * and only a fixed subset is drawn. Shapes ({@code rect}, {@code circle}, {@code ellipse}, {@code line},
 * {@code polyline}, {@code polygon}, {@code path}) in groups ({@code svg}, {@code g}) with fills, strokes, opacity,
 * transforms and linear or radial gradients; the same properties in a {@code style} attribute. Every other element is
 * skipped with what it holds (scripts, text, images, foreign objects, filters, {@code use}, styles), every other
 * attribute is ignored, and a paint is only a colour or {@code url(#gradient)} of this document: nothing is fetched,
 * run or referred to outside it.
 *
 * <p>A drawing is bounded before it is drawn: at most {@value #MAX_CHARS} characters, {@value #MAX_ELEMENTS}
 * elements, {@value #MAX_DEPTH} levels, {@value #MAX_NUMBERS} numbers, coordinates within {@value #MAX_COORDINATE}.
 * Anything outside is {@link Invalid}: the answer is not what was asked for.
 */
public final class CoverSvg {

    public static final int MAX_CHARS = 60_000;
    static final int MAX_ELEMENTS = 4_000;
    static final int MAX_DEPTH = 32;
    static final int MAX_NUMBERS = 100_000;
    static final double MAX_COORDINATE = 100_000;
    static final int MAX_STOPS = 32;
    static final double MAX_STROKE = 1_000;

    private static final Set<String> SHAPES = Set.of("rect", "circle", "ellipse", "line", "polyline", "polygon",
        "path");
    private static final Set<String> GRADIENTS = Set.of("linearGradient", "radialGradient");

    /** Why an SVG is not drawn. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    /** What is drawn: each shape with its place, paints and stroke, in document order. */
    public static final class Drawing {
        private final double viewX;
        private final double viewY;
        private final double viewWidth;
        private final double viewHeight;
        private final List<Item> items;

        private Drawing(double viewX, double viewY, double viewWidth, double viewHeight, List<Item> items) {
            this.viewX = viewX;
            this.viewY = viewY;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.items = List.copyOf(items);
        }

        /** How many shapes are drawn. */
        public int shapes() {
            return items.size();
        }

        /** Draws the picture over {@code width} × {@code height}, its view box scaled to cover it and centred. */
        public void paint(Graphics2D g, int width, int height) {
            AffineTransform saved = g.getTransform();
            java.awt.Composite composite = g.getComposite();
            double scale = Math.max(width / viewWidth, height / viewHeight);
            AffineTransform base = new AffineTransform(saved);
            base.translate((width - viewWidth * scale) / 2, (height - viewHeight * scale) / 2);
            base.scale(scale, scale);
            base.translate(-viewX, -viewY);
            try {
                for (Item item : items) {
                    AffineTransform at = new AffineTransform(base);
                    at.concatenate(item.transform);
                    g.setTransform(at);
                    Rectangle2D box = item.shape.getBounds2D();
                    paintWith(g, item.fill, box, item.opacity * item.style.fillOpacity, () -> g.fill(item.shape));
                    if (item.stroke != null && item.style.strokeWidth > 0) {
                        g.setStroke(new BasicStroke((float) item.style.strokeWidth, item.style.cap, item.style.join,
                            4f));
                        paintWith(g, item.stroke, box, item.opacity * item.style.strokeOpacity,
                            () -> g.draw(item.shape));
                    }
                }
            } finally {
                g.setTransform(saved);
                g.setComposite(composite);
            }
        }

        private void paintWith(Graphics2D g, PaintSpec spec, Rectangle2D box, double alpha, Runnable draw) {
            if (spec == null || alpha <= 0) {
                return;
            }
            Paint paint = spec.paint(box, viewWidth, viewHeight);
            if (paint == null) {
                return;
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, alpha)));
            g.setPaint(paint);
            draw.run();
        }
    }

    private record Item(Shape shape, AffineTransform transform, PaintSpec fill, PaintSpec stroke, Style style,
        double opacity) {}

    /** The inherited properties of an element. */
    private record Style(PaintSpec fill, PaintSpec stroke, double fillOpacity, double strokeOpacity,
        double strokeWidth, int cap, int join, int windingRule) {

        static final Style DEFAULT = new Style(new Solid(Color.BLACK), null, 1, 1, 1, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, Path2D.WIND_NON_ZERO);
    }

    private sealed interface PaintSpec permits Solid, GradientRef {
        Paint paint(Rectangle2D box, double viewWidth, double viewHeight);
    }

    private record Solid(Color color) implements PaintSpec {
        @Override
        public Paint paint(Rectangle2D box, double viewWidth, double viewHeight) {
            return color;
        }
    }

    private record GradientRef(Gradient gradient) implements PaintSpec {
        @Override
        public Paint paint(Rectangle2D box, double viewWidth, double viewHeight) {
            return gradient.paint(box, viewWidth, viewHeight);
        }
    }

    /** A gradient as written: its numbers in its units, its stops sorted. */
    private record Gradient(boolean radial, boolean userSpace, Map<String, String> geometry, AffineTransform transform,
        float[] fractions, Color[] colors) {

        Paint paint(Rectangle2D box, double viewWidth, double viewHeight) {
            if (colors.length == 0) {
                return null;
            }
            if (colors.length == 1) {
                return colors[0];
            }
            AffineTransform at = new AffineTransform();
            double w;
            double h;
            if (userSpace) {
                w = viewWidth;
                h = viewHeight;
            } else {
                if (box.getWidth() <= 0 || box.getHeight() <= 0) {
                    return null;
                }
                at.translate(box.getX(), box.getY());
                at.scale(box.getWidth(), box.getHeight());
                w = 1;
                h = 1;
            }
            at.concatenate(transform);
            if (radial) {
                double cx = length(geometry.get("cx"), 0.5, w, userSpace);
                double cy = length(geometry.get("cy"), 0.5, h, userSpace);
                double r = length(geometry.get("r"), 0.5, Math.hypot(w, h) / Math.sqrt(2), userSpace);
                double fx = length(geometry.get("fx"), Double.NaN, w, userSpace);
                double fy = length(geometry.get("fy"), Double.NaN, h, userSpace);
                if (!(r > 0)) {
                    return colors[colors.length - 1];
                }
                Point2D center = new Point2D.Double(cx, cy);
                Point2D focus = new Point2D.Double(Double.isNaN(fx) ? cx : fx, Double.isNaN(fy) ? cy : fy);
                return new RadialGradientPaint(center, (float) r, focus, fractions, colors,
                    MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, at);
            }
            double x1 = length(geometry.get("x1"), 0, w, userSpace);
            double y1 = length(geometry.get("y1"), 0, h, userSpace);
            double x2 = length(geometry.get("x2"), 1, w, userSpace);
            double y2 = length(geometry.get("y2"), 0, h, userSpace);
            if (x1 == x2 && y1 == y2) {
                return colors[colors.length - 1];
            }
            return new LinearGradientPaint(new Point2D.Double(x1, y1), new Point2D.Double(x2, y2), fractions, colors,
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, at);
        }

        /**
         * A gradient's length: a number, or a percentage of {@code size}; in bounding box units (where 1 is the box)
         * a plain number is already a fraction. Missing or unreadable: {@code fallback} (a fraction of {@code size}).
         */
        private static double length(String value, double fallback, double size, boolean userSpace) {
            if (value != null) {
                String v = value.strip();
                try {
                    if (v.endsWith("%")) {
                        return Double.parseDouble(v.substring(0, v.length() - 1)) / 100 * size;
                    }
                    double n = Double.parseDouble(v.endsWith("px") ? v.substring(0, v.length() - 2) : v);
                    if (Double.isFinite(n)) {
                        return n;
                    }
                } catch (NumberFormatException e) {
                    // the fallback
                }
            }
            return Double.isNaN(fallback) ? fallback : fallback * (userSpace ? size : 1);
        }
    }

    private CoverSvg() {}

    /** Reads an SVG: what is drawn, or why it is not. */
    public static Drawing parse(String svg) throws Invalid {
        if (svg == null || svg.isBlank()) {
            throw new Invalid("No SVG");
        }
        if (svg.length() > MAX_CHARS) {
            throw new Invalid("The SVG has " + svg.length() + " characters, more than " + MAX_CHARS);
        }
        Element root = document(svg).getDocumentElement();
        if (!"svg".equals(name(root))) {
            throw new Invalid("The root element is not svg");
        }
        Reader reader = new Reader();
        reader.count(root, 1);
        reader.collectGradients(root);
        double[] view = viewBox(root);
        reader.walk(root, new AffineTransform(), Style.DEFAULT, 1, view[2], view[3]);
        if (reader.items.isEmpty()) {
            throw new Invalid("The SVG draws nothing");
        }
        return new Drawing(view[0], view[1], view[2], view[3], reader.items);
    }

    private static Document document(String svg) throws Invalid {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // No DTD at all: no entities to expand, nothing external to read.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // not a reason to refuse
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("No external entities");
            });
            return builder.parse(new InputSource(new StringReader(svg)));
        } catch (SAXException | IOException e) {
            throw new Invalid("The SVG is not well-formed XML without a DTD");
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The XML parser cannot be made safe", e);
        }
    }

    private static String name(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    /** The view box: the root's {@code viewBox}, else its width and height, else the cover's size. */
    private static double[] viewBox(Element root) throws Invalid {
        String box = root.getAttribute("viewBox");
        if (!box.isBlank()) {
            List<Double> numbers = numbers(box);
            if (numbers.size() == 4 && numbers.get(2) > 0 && numbers.get(3) > 0
                && numbers.stream().allMatch(n -> Math.abs(n) <= MAX_COORDINATE)) {
                return new double[] {numbers.get(0), numbers.get(1), numbers.get(2), numbers.get(3)};
            }
            throw new Invalid("The viewBox is not four numbers with a positive width and height");
        }
        double width = plain(root.getAttribute("width"), CoverRenderer.WIDTH);
        double height = plain(root.getAttribute("height"), CoverRenderer.HEIGHT);
        if (!(width > 0) || !(height > 0) || width > MAX_COORDINATE || height > MAX_COORDINATE) {
            throw new Invalid("The SVG has no size");
        }
        return new double[] {0, 0, width, height};
    }

    private static double plain(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.strip();
        try {
            return Double.parseDouble(v.endsWith("px") ? v.substring(0, v.length() - 2) : v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<Double> numbers(String text) {
        List<Double> numbers = new ArrayList<>();
        Numbers scanner = new Numbers(text);
        while (scanner.more()) {
            Double n = scanner.number();
            if (n == null) {
                return List.of();
            }
            numbers.add(n);
        }
        return numbers;
    }

    // ---- reading the document ---------------------------------------------------------------------------------------

    private static final class Reader {
        final List<Item> items = new ArrayList<>();
        final Map<String, Element> gradientElements = new HashMap<>();
        final Map<String, Gradient> gradients = new HashMap<>();
        int numbers;

        /** Every element counts, drawn or not; too many or too deep refuses the whole. */
        void count(Element element, int depth) throws Invalid {
            if (depth > MAX_DEPTH) {
                throw new Invalid("The SVG nests deeper than " + MAX_DEPTH + " levels");
            }
            int[] total = {0};
            countFrom(element, depth, total);
        }

        private void countFrom(Element element, int depth, int[] total) throws Invalid {
            if (++total[0] > MAX_ELEMENTS) {
                throw new Invalid("The SVG has more than " + MAX_ELEMENTS + " elements");
            }
            if (depth > MAX_DEPTH) {
                throw new Invalid("The SVG nests deeper than " + MAX_DEPTH + " levels");
            }
            for (Element child : children(element)) {
                countFrom(child, depth + 1, total);
            }
        }

        void collectGradients(Element element) {
            for (Element child : children(element)) {
                if (GRADIENTS.contains(name(child)) && !child.getAttribute("id").isBlank()) {
                    gradientElements.putIfAbsent(child.getAttribute("id"), child);
                }
                collectGradients(child);
            }
        }

        void walk(Element element, AffineTransform parent, Style inherited, double opacity, double viewWidth,
            double viewHeight) throws Invalid {
            Map<String, String> properties = properties(element);
            if ("none".equals(properties.get("display")) || "hidden".equals(properties.get("visibility"))) {
                return;
            }
            AffineTransform transform = new AffineTransform(parent);
            String own = element.getAttribute("transform");
            if (!own.isBlank()) {
                AffineTransform parsed = transform(own);
                if (parsed == null) {
                    return;
                }
                transform.concatenate(parsed);
            }
            Style style = style(inherited, properties);
            double alpha = opacity * fraction(properties.get("opacity"), 1);
            String name = name(element);
            if ("svg".equals(name) || "g".equals(name)) {
                for (Element child : children(element)) {
                    walk(child, transform, style, alpha, viewWidth, viewHeight);
                }
            } else if (SHAPES.contains(name)) {
                Shape shape = shape(element, name, style.windingRule(), viewWidth, viewHeight);
                if (shape != null) {
                    items.add(new Item(shape, transform, style.fill(), style.stroke(), style, alpha));
                }
            }
            // Anything else (defs and gradients included: they are read where they are used) is not drawn.
        }

        // ---- properties ----------------------------------------------------------------------------------------------

        private static final Set<String> PROPERTIES = Set.of("fill", "stroke", "fill-opacity", "stroke-opacity",
            "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "opacity", "display", "visibility",
            "stop-color", "stop-opacity");

        /** The element's presentation properties: attributes, then the same names in its {@code style}. */
        static Map<String, String> properties(Element element) {
            Map<String, String> properties = new LinkedHashMap<>();
            for (String property : PROPERTIES) {
                String value = element.getAttribute(property);
                if (!value.isBlank()) {
                    properties.put(property, value.strip());
                }
            }
            String style = element.getAttribute("style");
            if (!style.isBlank()) {
                for (String declaration : style.split(";")) {
                    int colon = declaration.indexOf(':');
                    if (colon > 0) {
                        String property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                        String value = declaration.substring(colon + 1).replace("!important", "").strip();
                        if (PROPERTIES.contains(property) && !value.isEmpty()) {
                            properties.put(property, value);
                        }
                    }
                }
            }
            return properties;
        }

        private Style style(Style inherited, Map<String, String> p) {
            PaintSpec fill = p.containsKey("fill") ? paint(p.get("fill"), inherited.fill()) : inherited.fill();
            PaintSpec stroke = p.containsKey("stroke") ? paint(p.get("stroke"), inherited.stroke())
                : inherited.stroke();
            double width = inherited.strokeWidth();
            if (p.containsKey("stroke-width")) {
                double w = plain(p.get("stroke-width"), width);
                width = Double.isFinite(w) && w >= 0 ? Math.min(w, MAX_STROKE) : width;
            }
            int cap = switch (p.getOrDefault("stroke-linecap", "")) {
                case "round" -> BasicStroke.CAP_ROUND;
                case "square" -> BasicStroke.CAP_SQUARE;
                case "butt" -> BasicStroke.CAP_BUTT;
                default -> inherited.cap();
            };
            int join = switch (p.getOrDefault("stroke-linejoin", "")) {
                case "round" -> BasicStroke.JOIN_ROUND;
                case "bevel" -> BasicStroke.JOIN_BEVEL;
                case "miter" -> BasicStroke.JOIN_MITER;
                default -> inherited.join();
            };
            int rule = switch (p.getOrDefault("fill-rule", "")) {
                case "evenodd" -> Path2D.WIND_EVEN_ODD;
                case "nonzero" -> Path2D.WIND_NON_ZERO;
                default -> inherited.windingRule();
            };
            return new Style(fill, stroke, fraction(p.get("fill-opacity"), inherited.fillOpacity()),
                fraction(p.get("stroke-opacity"), inherited.strokeOpacity()), width, cap, join, rule);
        }

        /** A paint: none, a colour, or a gradient of this document (else its fallback); unreadable: inherited. */
        private PaintSpec paint(String value, PaintSpec inherited) {
            String v = value.strip();
            if ("none".equalsIgnoreCase(v)) {
                return null;
            }
            if (v.startsWith("url(")) {
                int close = v.indexOf(')');
                if (close < 0) {
                    return inherited;
                }
                String reference = v.substring(4, close).strip().replace("'", "").replace("\"", "");
                Gradient gradient = reference.startsWith("#") ? gradient(reference.substring(1), new HashSet<>())
                    : null;
                if (gradient != null) {
                    return new GradientRef(gradient);
                }
                String fallback = v.substring(close + 1).strip();
                return fallback.isEmpty() ? null : paint(fallback, inherited);
            }
            Color color = Colors.parse(v);
            return color == null ? inherited : new Solid(color);
        }

        private Gradient gradient(String id, Set<String> seen) {
            if (gradients.containsKey(id)) {
                return gradients.get(id);
            }
            Element element = gradientElements.get(id);
            if (element == null || !seen.add(id)) {
                return null;
            }
            boolean radial = "radialGradient".equals(name(element));
            Map<String, String> geometry = new HashMap<>();
            for (String attribute : List.of("x1", "y1", "x2", "y2", "cx", "cy", "r", "fx", "fy")) {
                if (!element.getAttribute(attribute).isBlank()) {
                    geometry.put(attribute, element.getAttribute(attribute));
                }
            }
            AffineTransform transform = element.getAttribute("gradientTransform").isBlank() ? new AffineTransform()
                : transform(element.getAttribute("gradientTransform"));
            if (transform == null) {
                transform = new AffineTransform();
            }
            List<float[]> offsets = new ArrayList<>();
            List<Color> colors = new ArrayList<>();
            for (Element stop : children(element)) {
                if (!"stop".equals(name(stop)) || colors.size() >= MAX_STOPS) {
                    continue;
                }
                Map<String, String> p = properties(stop);
                Color color = Colors.parse(p.getOrDefault("stop-color", "black"));
                if (color == null) {
                    color = Color.BLACK;
                }
                double alpha = fraction(p.get("stop-opacity"), 1) * color.getAlpha() / 255.0;
                colors.add(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) Math.round(alpha * 255)));
                offsets.add(new float[] {(float) fraction(stop.getAttribute("offset"), 0)});
            }
            // Stops given by another gradient of the document (href), when it has none of its own.
            String href = element.getAttribute("href");
            if (href.isBlank()) {
                href = element.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            }
            if (colors.isEmpty() && href.startsWith("#")) {
                Gradient base = gradient(href.substring(1), seen);
                if (base != null) {
                    for (int i = 0; i < base.colors().length; i++) {
                        colors.add(base.colors()[i]);
                        offsets.add(new float[] {base.fractions()[i]});
                    }
                }
            }
            float[] fractions = new float[colors.size()];
            float last = -1;
            for (int i = 0; i < fractions.length; i++) {
                float f = Math.max(Math.min(offsets.get(i)[0], 1f), 0f);
                // Each stop at or after the one before it, and strictly after it for Java's paints.
                f = Math.max(f, last + 1e-4f);
                fractions[i] = f;
                last = f;
            }
            if (fractions.length > 0 && fractions[fractions.length - 1] > 1) {
                float shift = fractions[fractions.length - 1] - 1;
                for (int i = 0; i < fractions.length; i++) {
                    fractions[i] = Math.max(0, fractions[i] - shift);
                }
                for (int i = 1; i < fractions.length; i++) {
                    if (fractions[i] <= fractions[i - 1]) {
                        // Too many stops crowded at the end: keep the first ones only.
                        fractions = java.util.Arrays.copyOf(fractions, i);
                        colors = colors.subList(0, i);
                        break;
                    }
                }
            }
            boolean userSpace = "userSpaceOnUse".equals(element.getAttribute("gradientUnits"));
            Gradient gradient = new Gradient(radial, userSpace, geometry, transform, fractions,
                colors.toArray(Color[]::new));
            gradients.put(id, gradient);
            return gradient;
        }

        // ---- shapes --------------------------------------------------------------------------------------------------

        private Shape shape(Element e, String name, int windingRule, double viewWidth, double viewHeight)
            throws Invalid {
            return switch (name) {
                case "rect" -> {
                    double w = length(e, "width", viewWidth);
                    double h = length(e, "height", viewHeight);
                    if (!(w > 0) || !(h > 0)) {
                        yield null;
                    }
                    double x = length(e, "x", viewWidth);
                    double y = length(e, "y", viewHeight);
                    double rx = e.getAttribute("rx").isBlank() ? Double.NaN : length(e, "rx", viewWidth);
                    double ry = e.getAttribute("ry").isBlank() ? Double.NaN : length(e, "ry", viewHeight);
                    if (Double.isNaN(rx)) {
                        rx = Double.isNaN(ry) ? 0 : ry;
                    }
                    if (Double.isNaN(ry)) {
                        ry = rx;
                    }
                    rx = Math.min(Math.max(rx, 0), w / 2);
                    ry = Math.min(Math.max(ry, 0), h / 2);
                    yield rx > 0 || ry > 0 ? new RoundRectangle2D.Double(x, y, w, h, rx * 2, ry * 2)
                        : new Rectangle2D.Double(x, y, w, h);
                }
                case "circle" -> {
                    double r = length(e, "r", Math.hypot(viewWidth, viewHeight) / Math.sqrt(2));
                    yield r > 0 ? new Ellipse2D.Double(length(e, "cx", viewWidth) - r, length(e, "cy", viewHeight) - r,
                        2 * r, 2 * r) : null;
                }
                case "ellipse" -> {
                    double rx = length(e, "rx", viewWidth);
                    double ry = length(e, "ry", viewHeight);
                    yield rx > 0 && ry > 0 ? new Ellipse2D.Double(length(e, "cx", viewWidth) - rx,
                        length(e, "cy", viewHeight) - ry, 2 * rx, 2 * ry) : null;
                }
                case "line" -> new Line2D.Double(length(e, "x1", viewWidth), length(e, "y1", viewHeight),
                    length(e, "x2", viewWidth), length(e, "y2", viewHeight));
                case "polyline", "polygon" -> {
                    List<Double> points = counted(numbers(e.getAttribute("points")));
                    if (points.size() < 4) {
                        yield null;
                    }
                    Path2D path = new Path2D.Double(windingRule);
                    path.moveTo(points.get(0), points.get(1));
                    for (int i = 2; i + 1 < points.size(); i += 2) {
                        path.lineTo(points.get(i), points.get(i + 1));
                    }
                    if ("polygon".equals(name)) {
                        path.closePath();
                    }
                    yield path;
                }
                case "path" -> path(e.getAttribute("d"), windingRule);
                default -> null;
            };
        }

        private List<Double> counted(List<Double> values) throws Invalid {
            numbers += values.size();
            if (numbers > MAX_NUMBERS) {
                throw new Invalid("The SVG has more than " + MAX_NUMBERS + " numbers");
            }
            for (double n : values) {
                if (Math.abs(n) > MAX_COORDINATE) {
                    throw new Invalid("The SVG has a coordinate beyond " + MAX_COORDINATE);
                }
            }
            return values;
        }

        /** A length attribute: a number (px allowed) or a percentage of {@code size}; missing or unreadable is 0. */
        private double length(Element e, String attribute, double size) throws Invalid {
            String value = e.getAttribute(attribute).strip();
            if (value.isEmpty()) {
                return 0;
            }
            double n;
            try {
                n = value.endsWith("%") ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100 * size
                    : Double.parseDouble(value.endsWith("px") ? value.substring(0, value.length() - 2) : value);
            } catch (NumberFormatException ex) {
                return 0;
            }
            if (!Double.isFinite(n)) {
                return 0;
            }
            counted(List.of(n));
            return n;
        }

        /** A path's {@code d}: every command, absolute and relative; it stops at the first error, as SVG does. */
        private Path2D path(String d, int windingRule) throws Invalid {
            Path2D path = new Path2D.Double(windingRule);
            Numbers in = new Numbers(d);
            double x = 0;
            double y = 0;
            double startX = 0;
            double startY = 0;
            double controlX = 0;
            double controlY = 0;
            char previous = ' ';
            char command = ' ';
            boolean started = false;
            int read = 0;
            while (in.more()) {
                char c = in.command();
                if (c != 0) {
                    command = c;
                } else if (command == ' ' || command == 'Z' || command == 'z') {
                    break;
                }
                boolean relative = Character.isLowerCase(command);
                char upper = Character.toUpperCase(command);
                double[] a;
                switch (upper) {
                    case 'M' -> {
                        if ((a = in.take(2)) == null) {
                            return finish(path, started, read);
                        }
                        x = relative ? x + a[0] : a[0];
                        y = relative ? y + a[1] : a[1];
                        path.moveTo(x, y);
                        startX = x;
                        startY = y;
                        started = true;
                        // Further pairs are lines.
                        command = relative ? 'l' : 'L';
                    }
                    case 'L' -> {
                        if (!started || (a = in.take(2)) == null) {
                            return finish(path, started, read);
                        }
                        x = relative ? x + a[0] : a[0];
                        y = relative ? y + a[1] : a[1];
                        path.lineTo(x, y);
                    }
                    case 'H' -> {
                        if (!started || (a = in.take(1)) == null) {
                            return finish(path, started, read);
                        }
                        x = relative ? x + a[0] : a[0];
                        path.lineTo(x, y);
                    }
                    case 'V' -> {
                        if (!started || (a = in.take(1)) == null) {
                            return finish(path, started, read);
                        }
                        y = relative ? y + a[0] : a[0];
                        path.lineTo(x, y);
                    }
                    case 'C', 'S' -> {
                        int n = upper == 'C' ? 6 : 4;
                        if (!started || (a = in.take(n)) == null) {
                            return finish(path, started, read);
                        }
                        double x1;
                        double y1;
                        int i = 0;
                        if (upper == 'C') {
                            x1 = relative ? x + a[0] : a[0];
                            y1 = relative ? y + a[1] : a[1];
                            i = 2;
                        } else if ("CcSs".indexOf(previous) >= 0) {
                            x1 = 2 * x - controlX;
                            y1 = 2 * y - controlY;
                        } else {
                            x1 = x;
                            y1 = y;
                        }
                        double x2 = relative ? x + a[i] : a[i];
                        double y2 = relative ? y + a[i + 1] : a[i + 1];
                        double ex = relative ? x + a[i + 2] : a[i + 2];
                        double ey = relative ? y + a[i + 3] : a[i + 3];
                        path.curveTo(x1, y1, x2, y2, ex, ey);
                        controlX = x2;
                        controlY = y2;
                        x = ex;
                        y = ey;
                    }
                    case 'Q', 'T' -> {
                        int n = upper == 'Q' ? 4 : 2;
                        if (!started || (a = in.take(n)) == null) {
                            return finish(path, started, read);
                        }
                        double qx;
                        double qy;
                        int i = 0;
                        if (upper == 'Q') {
                            qx = relative ? x + a[0] : a[0];
                            qy = relative ? y + a[1] : a[1];
                            i = 2;
                        } else if ("QqTt".indexOf(previous) >= 0) {
                            qx = 2 * x - controlX;
                            qy = 2 * y - controlY;
                        } else {
                            qx = x;
                            qy = y;
                        }
                        double ex = relative ? x + a[i] : a[i];
                        double ey = relative ? y + a[i + 1] : a[i + 1];
                        path.quadTo(qx, qy, ex, ey);
                        controlX = qx;
                        controlY = qy;
                        x = ex;
                        y = ey;
                    }
                    case 'A' -> {
                        if (!started || (a = in.arc()) == null) {
                            return finish(path, started, read);
                        }
                        double ex = relative ? x + a[5] : a[5];
                        double ey = relative ? y + a[6] : a[6];
                        arc(path, x, y, a[0], a[1], a[2], a[3] != 0, a[4] != 0, ex, ey);
                        x = ex;
                        y = ey;
                    }
                    case 'Z' -> {
                        if (started) {
                            path.closePath();
                        }
                        x = startX;
                        y = startY;
                    }
                    default -> {
                        return finish(path, started, read);
                    }
                }
                read = in.read;
                if (read > MAX_NUMBERS) {
                    throw new Invalid("A path has more than " + MAX_NUMBERS + " numbers");
                }
                previous = command;
                if (upper == 'Z') {
                    command = ' ';
                    previous = 'Z';
                }
            }
            return finish(path, started, in.read);
        }

        private Path2D finish(Path2D path, boolean started, int read) throws Invalid {
            numbers += read;
            if (numbers > MAX_NUMBERS) {
                throw new Invalid("The SVG has more than " + MAX_NUMBERS + " numbers");
            }
            Rectangle2D bounds = started ? path.getBounds2D() : null;
            if (bounds == null || Math.abs(bounds.getMinX()) > MAX_COORDINATE || Math.abs(bounds.getMaxX())
                > MAX_COORDINATE || Math.abs(bounds.getMinY()) > MAX_COORDINATE
                || Math.abs(bounds.getMaxY()) > MAX_COORDINATE) {
                if (bounds != null) {
                    throw new Invalid("The SVG has a coordinate beyond " + MAX_COORDINATE);
                }
                return null;
            }
            return path;
        }
    }

    /**
     * An elliptical arc from (x0, y0) to (x, y) as SVG defines it (the endpoint parameterisation, SVG 1.1 F.6.5), as
     * the matching arc of the ellipse, rotated.
     */
    static void arc(Path2D path, double x0, double y0, double rx, double ry, double angle, boolean largeArc,
        boolean sweep, double x, double y) {
        if (x0 == x && y0 == y) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        double dx2 = (x0 - x) / 2;
        double dy2 = (y0 - y) / 2;
        double radians = Math.toRadians(angle % 360);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x1 = cos * dx2 + sin * dy2;
        double y1 = -sin * dx2 + cos * dy2;
        double prx = rx * rx;
        double pry = ry * ry;
        double px1 = x1 * x1;
        double py1 = y1 * y1;
        double check = px1 / prx + py1 / pry;
        if (check > 1) {
            double scale = Math.sqrt(check);
            rx *= scale;
            ry *= scale;
            prx = rx * rx;
            pry = ry * ry;
        }
        double sign = largeArc == sweep ? -1 : 1;
        double sq = (prx * pry - prx * py1 - pry * px1) / (prx * py1 + pry * px1);
        double coef = sign * Math.sqrt(Math.max(sq, 0));
        double cx1 = coef * (rx * y1 / ry);
        double cy1 = coef * -(ry * x1 / rx);
        double cx = (x0 + x) / 2 + (cos * cx1 - sin * cy1);
        double cy = (y0 + y) / 2 + (sin * cx1 + cos * cy1);
        double ux = (x1 - cx1) / rx;
        double uy = (y1 - cy1) / ry;
        double vx = (-x1 - cx1) / rx;
        double vy = (-y1 - cy1) / ry;
        double start = Math.toDegrees((uy < 0 ? -1 : 1) * Math.acos(clamp(ux / Math.hypot(ux, uy))));
        double extent = Math.toDegrees((ux * vy - uy * vx < 0 ? -1 : 1)
            * Math.acos(clamp((ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy)))));
        if (!sweep && extent > 0) {
            extent -= 360;
        } else if (sweep && extent < 0) {
            extent += 360;
        }
        Arc2D.Double arc = new Arc2D.Double(cx - rx, cy - ry, rx * 2, ry * 2, -(start % 360), -(extent % 360),
            Arc2D.OPEN);
        Shape rotated = AffineTransform.getRotateInstance(radians, cx, cy).createTransformedShape(arc);
        path.append(rotated, true);
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 1 : Math.max(-1, Math.min(1, v));
    }

    private static List<Element> children(Element element) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    /** An opacity: a number or a percentage, within 0..1; missing or unreadable keeps {@code fallback}. */
    static double fraction(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.strip();
        try {
            double n = v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100
                : Double.parseDouble(v);
            return Double.isFinite(n) ? Math.max(0, Math.min(1, n)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code transform}: a list of matrix, translate, scale, rotate, skewX, skewY; null when unreadable. */
    static AffineTransform transform(String text) {
        AffineTransform result = new AffineTransform();
        String rest = text.strip();
        while (!rest.isEmpty()) {
            int open = rest.indexOf('(');
            int close = rest.indexOf(')');
            if (open <= 0 || close < open) {
                return null;
            }
            String name = rest.substring(0, open).strip();
            List<Double> a = numbers(rest.substring(open + 1, close));
            if (a.stream().anyMatch(n -> !Double.isFinite(n) || Math.abs(n) > MAX_COORDINATE)) {
                return null;
            }
            AffineTransform step = switch (name) {
                case "matrix" -> a.size() == 6
                    ? new AffineTransform(a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), a.get(5)) : null;
                case "translate" -> a.size() == 1 || a.size() == 2
                    ? AffineTransform.getTranslateInstance(a.get(0), a.size() == 2 ? a.get(1) : 0) : null;
                case "scale" -> a.size() == 1 || a.size() == 2
                    ? AffineTransform.getScaleInstance(a.get(0), a.size() == 2 ? a.get(1) : a.get(0)) : null;
                case "rotate" -> a.size() == 1 ? AffineTransform.getRotateInstance(Math.toRadians(a.get(0)))
                    : a.size() == 3 ? AffineTransform.getRotateInstance(Math.toRadians(a.get(0)), a.get(1), a.get(2))
                    : null;
                case "skewX" -> a.size() == 1 ? new AffineTransform(1, 0, Math.tan(Math.toRadians(a.get(0))), 1, 0, 0)
                    : null;
                case "skewY" -> a.size() == 1 ? new AffineTransform(1, Math.tan(Math.toRadians(a.get(0))), 0, 1, 0, 0)
                    : null;
                default -> null;
            };
            if (step == null) {
                return null;
            }
            result.concatenate(step);
            rest = rest.substring(close + 1).strip();
            if (rest.startsWith(",")) {
                rest = rest.substring(1).strip();
            }
        }
        return result;
    }

    /** Numbers and path commands as SVG writes them: "M10-5.5.5e2" is M, 10, -5.5, 0.5e2. */
    static final class Numbers {
        private final String text;
        private int at;
        int read;

        Numbers(String text) {
            this.text = text == null ? "" : text;
        }

        private void skip() {
            while (at < text.length() && (Character.isWhitespace(text.charAt(at)) || text.charAt(at) == ',')) {
                at++;
            }
        }

        boolean more() {
            skip();
            return at < text.length();
        }

        /** The command letter here, consumed; 0 when a number comes next. */
        char command() {
            skip();
            char c = text.charAt(at);
            if (Character.isLetter(c) && c != 'e' && c != 'E') {
                at++;
                return c;
            }
            return 0;
        }

        /** The next number, or null when there is none here. */
        Double number() {
            skip();
            int from = at;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                at++;
            }
            boolean digits = false;
            boolean dot = false;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (Character.isDigit(c)) {
                    digits = true;
                } else if (c == '.' && !dot) {
                    dot = true;
                } else {
                    break;
                }
                at++;
            }
            if (digits && at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                int mark = at;
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                int exponent = at;
                while (at < text.length() && Character.isDigit(text.charAt(at))) {
                    at++;
                }
                if (at == exponent) {
                    at = mark;
                }
            }
            if (!digits) {
                at = from;
                return null;
            }
            double n = Double.parseDouble(text.substring(from, at));
            if (!Double.isFinite(n)) {
                return null;
            }
            read++;
            return n;
        }

        /** {@code n} numbers, or null when they are not all here. */
        double[] take(int n) {
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                Double v = number();
                if (v == null) {
                    return null;
                }
                values[i] = v;
            }
            return values;
        }

        /** An arc's seven values: its flags may be written without separators ("a5 5 0 011 1"). */
        double[] arc() {
            double[] a = take(3);
            if (a == null) {
                return null;
            }
            double[] values = new double[7];
            System.arraycopy(a, 0, values, 0, 3);
            for (int i = 3; i < 5; i++) {
                skip();
                if (at >= text.length() || (text.charAt(at) != '0' && text.charAt(at) != '1')) {
                    return null;
                }
                values[i] = text.charAt(at) - '0';
                at++;
                read++;
            }
            double[] end = take(2);
            if (end == null) {
                return null;
            }
            values[5] = end[0];
            values[6] = end[1];
            return values;
        }
    }

    /** CSS colours as SVG files write them. */
    static final class Colors {

        private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("white", 0xFFFFFF), Map.entry("red", 0xFF0000),
            Map.entry("green", 0x008000), Map.entry("blue", 0x0000FF), Map.entry("yellow", 0xFFFF00),
            Map.entry("orange", 0xFFA500), Map.entry("purple", 0x800080), Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080), Map.entry("silver", 0xC0C0C0), Map.entry("maroon", 0x800000),
            Map.entry("olive", 0x808000), Map.entry("lime", 0x00FF00), Map.entry("aqua", 0x00FFFF),
            Map.entry("cyan", 0x00FFFF), Map.entry("teal", 0x008080), Map.entry("navy", 0x000080),
            Map.entry("fuchsia", 0xFF00FF), Map.entry("magenta", 0xFF00FF), Map.entry("pink", 0xFFC0CB),
            Map.entry("brown", 0xA52A2A), Map.entry("gold", 0xFFD700), Map.entry("beige", 0xF5F5DC),
            Map.entry("ivory", 0xFFFFF0), Map.entry("khaki", 0xF0E68C), Map.entry("coral", 0xFF7F50),
            Map.entry("salmon", 0xFA8072), Map.entry("tomato", 0xFF6347), Map.entry("crimson", 0xDC143C),
            Map.entry("indigo", 0x4B0082), Map.entry("violet", 0xEE82EE), Map.entry("orchid", 0xDA70D6),
            Map.entry("plum", 0xDDA0DD), Map.entry("lavender", 0xE6E6FA), Map.entry("skyblue", 0x87CEEB),
            Map.entry("steelblue", 0x4682B4), Map.entry("royalblue", 0x4169E1), Map.entry("dodgerblue", 0x1E90FF),
            Map.entry("deepskyblue", 0x00BFFF), Map.entry("lightblue", 0xADD8E6), Map.entry("lightgreen", 0x90EE90),
            Map.entry("darkgreen", 0x006400), Map.entry("forestgreen", 0x228B22), Map.entry("seagreen", 0x2E8B57),
            Map.entry("limegreen", 0x32CD32), Map.entry("olivedrab", 0x6B8E23), Map.entry("darkblue", 0x00008B),
            Map.entry("darkred", 0x8B0000), Map.entry("darkorange", 0xFF8C00), Map.entry("darkgray", 0xA9A9A9),
            Map.entry("darkgrey", 0xA9A9A9), Map.entry("lightgray", 0xD3D3D3), Map.entry("lightgrey", 0xD3D3D3),
            Map.entry("dimgray", 0x696969), Map.entry("dimgrey", 0x696969), Map.entry("slategray", 0x708090),
            Map.entry("slategrey", 0x708090), Map.entry("whitesmoke", 0xF5F5F5), Map.entry("snow", 0xFFFAFA),
            Map.entry("tan", 0xD2B48C), Map.entry("sienna", 0xA0522D), Map.entry("chocolate", 0xD2691E),
            Map.entry("peru", 0xCD853F), Map.entry("wheat", 0xF5DEB3), Map.entry("turquoise", 0x40E0D0),
            Map.entry("midnightblue", 0x191970), Map.entry("goldenrod", 0xDAA520), Map.entry("hotpink", 0xFF69B4),
            Map.entry("lightyellow", 0xFFFFE0), Map.entry("mintcream", 0xF5FFFA), Map.entry("aliceblue", 0xF0F8FF));

        private Colors() {}

        /** A colour, or null when it is not one this reads. */
        static Color parse(String value) {
            if (value == null) {
                return null;
            }
            String v = value.strip().toLowerCase(Locale.ROOT);
            if ("transparent".equals(v)) {
                return new Color(0, 0, 0, 0);
            }
            if (v.startsWith("#")) {
                String hex = v.substring(1);
                if (!hex.matches("[0-9a-f]+")) {
                    return null;
                }
                return switch (hex.length()) {
                    case 3 -> new Color(Integer.parseInt(hex.substring(0, 1).repeat(2), 16),
                        Integer.parseInt(hex.substring(1, 2).repeat(2), 16),
                        Integer.parseInt(hex.substring(2, 3).repeat(2), 16));
                    case 6 -> new Color(Integer.parseInt(hex, 16));
                    case 8 -> new Color(Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16),
                        Integer.parseInt(hex.substring(6, 8), 16));
                    default -> null;
                };
            }
            if (v.startsWith("rgb") || v.startsWith("hsl")) {
                int open = v.indexOf('(');
                int close = v.lastIndexOf(')');
                if (open < 0 || close < open) {
                    return null;
                }
                String[] parts = v.substring(open + 1, close).replace("/", " ").replace(",", " ").strip()
                    .split("\\s+");
                if (parts.length < 3 || parts.length > 4) {
                    return null;
                }
                try {
                    double alpha = parts.length == 4 ? fraction(parts[3], 1) : 1;
                    if (v.startsWith("rgb")) {
                        int[] rgb = new int[3];
                        for (int i = 0; i < 3; i++) {
                            double n = parts[i].endsWith("%")
                                ? Double.parseDouble(parts[i].substring(0, parts[i].length() - 1)) * 2.55
                                : Double.parseDouble(parts[i]);
                            rgb[i] = (int) Math.round(Math.max(0, Math.min(255, n)));
                        }
                        return new Color(rgb[0], rgb[1], rgb[2], (int) Math.round(alpha * 255));
                    }
                    double h = Double.parseDouble(parts[0].replace("deg", "")) / 360;
                    double s = Double.parseDouble(parts[1].replace("%", "")) / 100;
                    double l = Double.parseDouble(parts[2].replace("%", "")) / 100;
                    int rgb = hsl(h - Math.floor(h), Math.max(0, Math.min(1, s)), Math.max(0, Math.min(1, l)));
                    return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (int) Math.round(alpha * 255));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            Integer named = NAMED.get(v);
            return named == null ? null : new Color(named);
        }

        private static int hsl(double h, double s, double l) {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            int r = (int) Math.round(hue(p, q, h + 1.0 / 3) * 255);
            int g = (int) Math.round(hue(p, q, h) * 255);
            int b = (int) Math.round(hue(p, q, h - 1.0 / 3) * 255);
            return (r << 16) | (g << 8) | b;
        }

        private static double hue(double p, double q, double t) {
            if (t < 0) {
                t += 1;
            }
            if (t > 1) {
                t -= 1;
            }
            if (t < 1.0 / 6) {
                return p + (q - p) * 6 * t;
            }
            if (t < 0.5) {
                return q;
            }
            if (t < 2.0 / 3) {
                return p + (q - p) * (2.0 / 3 - t) * 6;
            }
            return p;
        }
    }
}
