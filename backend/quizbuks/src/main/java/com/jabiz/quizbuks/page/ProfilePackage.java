package com.jabiz.quizbuks.page;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.QbContent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sponsor's profile package (docs/quizbuks/plans/Q100-profile-package.md): their basics, public page, products and
 * mail server settings in one JSON document, as the console exports it and as other tools (the business site's
 * builder, an AI) write it. This reads the document's shape only, every problem at once with its path; what may be
 * saved is decided by the processes the import runs (the same rules as typing it in). Pure Java.
 *
 * <p>Texts are written per language ({@code {"ja": …, "zh": …, "en": …}}), the package's first language being the base;
 * a plain string is the base text. An image is the key of an entry of {@code files} (its bytes, base64), a link
 * ({@code {"url": "https://…"}}), or {@code null} (none); an image left out of the package keeps the one there is
 * (a package written without images, then imported, keeps the images added since: D-Q100-2).
 */
public record ProfilePackage(List<String> languages, Basics basics, Page page, List<Product> products,
    MailServer mailServer, Map<String, FileEntry> files) {

    public static final String FORMAT = "quizbuks-profile";
    public static final int SCHEMA = 1;

    public static final String FORMAT_INVALID = "QB_PROFILE_PACKAGE_FORMAT";
    public static final String FIELD_INVALID = "QB_PROFILE_PACKAGE_FIELD";
    public static final String FILE_INVALID = "QB_PROFILE_PACKAGE_FILE";
    public static final List<String> CODES = List.of(FORMAT_INVALID, FIELD_INVALID, FILE_INVALID);

    /** An image: the key of an entry of {@code files}, or a link; at most one is set. */
    public record Image(String key, String url) {
        /** Left out of the package: the image there is stays. */
        public static final Image KEEP = new Image(null, null);

        public boolean isLink() {
            return url != null;
        }

        public boolean isFile() {
            return key != null;
        }
    }

    public record Basics(String contactName, String company, Map<String, String> intro, Image avatar) {}

    public record Page(String handle, Map<String, String> headline, String website, Image cover) {}

    /**
     * @param productId the product's id when exported (same environment)
     * @param code      the sponsor's own code of the product (Q100e); the product is found by it first
     */
    public record Product(String productId, String code, Map<String, String> name, Map<String, String> description,
        BigDecimal price, String currency, String shopUrl, boolean listed, Image image) {}

    /** The settings of a mail server, never its password. */
    public record MailServer(String host, int port, String tls, String username, String fromAddress,
        String fromName) {}

    /** A file of the package, without its bytes (the server never reads them; the console uploads them). */
    public record FileEntry(String type, long size) {}

    /** What reading gives: the package when its shape is right, and every problem otherwise. */
    public record Read(ProfilePackage profile, List<Violation> problems) {}

    /** The base language. */
    public String base() {
        return languages.getFirst();
    }

    /** Every image of the package with its path (`basics.avatar`, `products[2].image`). */
    public Map<String, Image> images() {
        Map<String, Image> images = new LinkedHashMap<>();
        if (basics != null && given(basics.avatar())) {
            images.put("basics.avatar", basics.avatar());
        }
        if (page != null && given(page.cover())) {
            images.put("page.cover", page.cover());
        }
        for (int i = 0; i < products.size(); i++) {
            if (given(products.get(i).image())) {
                images.put("products[" + i + "].image", products.get(i).image());
            }
        }
        return images;
    }

    private static boolean given(Image image) {
        return image != null && image != Image.KEEP;
    }

    public static Read read(Map<String, Object> document) {
        List<Violation> problems = new ArrayList<>();
        if (document == null || !FORMAT.equals(document.get("format"))) {
            problems.add(new Violation("format", FORMAT_INVALID, "Not a profile package", Map.of()));
            return new Read(null, problems);
        }
        Object schema = document.get("schema");
        if (!(schema instanceof Number number) || number.intValue() != SCHEMA) {
            problems.add(new Violation("schema", FORMAT_INVALID, "A profile package of schema " + SCHEMA
                + " only", Map.of("schema", String.valueOf(schema))));
            return new Read(null, problems);
        }
        Reader reader = new Reader(problems);
        List<String> languages = reader.languages(document.get("languages"));
        Map<String, FileEntry> files = reader.files(document.get("files"));
        reader.files = files;
        reader.languages = languages;
        Basics basics = reader.basics(document.get("basics"));
        Page page = reader.page(document.get("page"));
        List<Product> products = reader.products(document.get("products"));
        MailServer mailServer = reader.mailServer(document.get("mailServer"));
        return new Read(problems.isEmpty() ? new ProfilePackage(languages, basics, page, products, mailServer, files)
            : null, List.copyOf(problems));
    }

    private static final class Reader {
        private final List<Violation> problems;
        private Map<String, FileEntry> files = Map.of();
        private List<String> languages = List.of();

        Reader(List<Violation> problems) {
            this.problems = problems;
        }

        private void problem(String path, String code, String message) {
            problems.add(new Violation(path, code, message, Map.of("path", path)));
        }

        List<String> languages(Object value) {
            List<String> languages = new ArrayList<>();
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                problem("languages", FIELD_INVALID, "The package's languages, its base first");
                return List.of(QbContent.LANGUAGES.getFirst());
            }
            for (Object language : list) {
                if (!(language instanceof String code) || !QbContent.LANGUAGES.contains(code)
                    || languages.contains(code)) {
                    problem("languages", FIELD_INVALID, "Not a language of the platform, or given twice: " + language);
                } else {
                    languages.add(code);
                }
            }
            return languages.isEmpty() ? List.of(QbContent.LANGUAGES.getFirst()) : List.copyOf(languages);
        }

        Map<String, FileEntry> files(Object value) {
            Map<String, FileEntry> files = new LinkedHashMap<>();
            if (value == null) {
                return files;
            }
            if (!(value instanceof Map<?, ?> map)) {
                problem("files", FIELD_INVALID, "The files by key");
                return files;
            }
            map.forEach((key, entry) -> {
                String path = "files." + key;
                if (!(entry instanceof Map<?, ?> fields) || !(fields.get("type") instanceof String type)
                    || !type.startsWith("image/") || !(fields.get("size") instanceof Number size)
                    || size.longValue() <= 0) {
                    problem(path, FILE_INVALID, "A file is an image with its type, size and data");
                    return;
                }
                files.put(String.valueOf(key), new FileEntry(type, size.longValue()));
            });
            return files;
        }

        private String text(Object value, String path, boolean required) {
            if (value == null || value instanceof String s && s.isBlank()) {
                if (required) {
                    problem(path, FIELD_INVALID, "Required");
                }
                return null;
            }
            if (!(value instanceof String s)) {
                problem(path, FIELD_INVALID, "A text");
                return null;
            }
            return s.strip();
        }

        /** Texts by language; a plain string is the base text. */
        private Map<String, String> texts(Object value, String path, boolean required) {
            Map<String, String> texts = new LinkedHashMap<>();
            if (value instanceof String s) {
                if (!s.isBlank()) {
                    texts.put(languages.getFirst(), s.strip());
                }
            } else if (value instanceof Map<?, ?> map) {
                map.forEach((language, text) -> {
                    String at = path + "." + language;
                    if (!languages.contains(String.valueOf(language))) {
                        problem(at, FIELD_INVALID, "Not one of the package's languages");
                        return;
                    }
                    String clean = text(text, at, false);
                    if (clean != null) {
                        texts.put(String.valueOf(language), clean);
                    }
                });
            } else if (value != null) {
                problem(path, FIELD_INVALID, "Texts by language");
            }
            if (required && !texts.containsKey(languages.getFirst())) {
                problem(path + "." + languages.getFirst(), FIELD_INVALID, "Required in the base language");
            }
            return texts;
        }

        private Image image(Map<?, ?> owner, String field, String path) {
            if (!owner.containsKey(field)) {
                return Image.KEEP;
            }
            Object value = owner.get(field);
            if (value == null) {
                return null;
            }
            if (value instanceof String key) {
                if (!files.containsKey(key)) {
                    problem(path, FILE_INVALID, "No file of key " + key);
                    return null;
                }
                return new Image(key, null);
            }
            if (value instanceof Map<?, ?> map && map.get("url") instanceof String url) {
                if (!url.strip().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                    problem(path, FILE_INVALID, "An image link starts with https://");
                    return null;
                }
                return new Image(null, url.strip());
            }
            problem(path, FILE_INVALID, "An image is a file's key or a link");
            return null;
        }

        Basics basics(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                problem("basics", FIELD_INVALID, "The basics");
                return null;
            }
            return new Basics(text(map.get("contactName"), "basics.contactName", true),
                text(map.get("company"), "basics.company", true), texts(map.get("intro"), "basics.intro", false),
                image(map, "avatar", "basics.avatar"));
        }

        Page page(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                problem("page", FIELD_INVALID, "The page");
                return null;
            }
            return new Page(text(map.get("handle"), "page.handle", true),
                texts(map.get("headline"), "page.headline", true), text(map.get("website"), "page.website", false),
                image(map, "cover", "page.cover"));
        }

        List<Product> products(Object value) {
            List<Product> products = new ArrayList<>();
            if (value == null) {
                return products;
            }
            if (!(value instanceof List<?> list)) {
                problem("products", FIELD_INVALID, "The products, in their order");
                return products;
            }
            if (list.size() > QbSponsorProducts.MAX_PRODUCTS) {
                problems.add(new Violation("products", SponsorPageCodes.PRODUCT_LIMIT, "At most "
                    + QbSponsorProducts.MAX_PRODUCTS + " products", Map.of("max", QbSponsorProducts.MAX_PRODUCTS)));
            }
            for (int i = 0; i < list.size(); i++) {
                String path = "products[" + i + "]";
                if (!(list.get(i) instanceof Map<?, ?> map)) {
                    problem(path, FIELD_INVALID, "A product");
                    continue;
                }
                BigDecimal price = null;
                Object rawPrice = map.get("price");
                try {
                    price = rawPrice instanceof Number n ? new BigDecimal(n.toString())
                        : rawPrice instanceof String s ? new BigDecimal(s.strip()) : null;
                } catch (NumberFormatException e) {
                    price = null;
                }
                if (price == null) {
                    problem(path + ".price", FIELD_INVALID, "A price");
                }
                Object productId = map.get("productId");
                Object listed = map.get("listed");
                String code = text(map.get("code"), path + ".code", false);
                if (code != null && !QbSponsorProducts.CODE_PATTERN.matcher(code).matches()) {
                    problems.add(new Violation(path + ".code", SponsorPageCodes.PRODUCT_CODE_FORMAT, "A product code "
                        + "is letters, digits and . _ -", Map.of("code", code, "path", path + ".code")));
                    code = null;
                }
                products.add(new Product(productId instanceof String s && !s.isBlank() ? s : null, code,
                    texts(map.get("name"), path + ".name", true), texts(map.get("description"),
                    path + ".description", false), price, text(map.get("currency"), path + ".currency", true),
                    text(map.get("shopUrl"), path + ".shopUrl", true), !Boolean.FALSE.equals(listed),
                    image(map, "image", path + ".image")));
            }
            return products;
        }

        MailServer mailServer(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                problem("mailServer", FIELD_INVALID, "The mail server's settings");
                return null;
            }
            if (map.containsKey("password")) {
                problem("mailServer.password", FIELD_INVALID, "A package never holds the password: it is typed in "
                    + "on import");
            }
            Object port = map.get("port");
            if (!(port instanceof Number)) {
                problem("mailServer.port", FIELD_INVALID, "A port number");
            }
            return new MailServer(text(map.get("host"), "mailServer.host", true),
                port instanceof Number n ? n.intValue() : 0, text(map.get("tls"), "mailServer.tls", true),
                text(map.get("username"), "mailServer.username", true),
                text(map.get("fromAddress"), "mailServer.fromAddress", true),
                text(map.get("fromName"), "mailServer.fromName", false));
        }
    }
}
