package com.jabiz.finance.io;

import com.jabiz.finance.calc.StatementCheck;
import com.jabiz.imports.ImportFileException;
import com.jabiz.imports.ImportParser;
import com.jabiz.imports.ParseLimits;
import com.jabiz.imports.RawRecord;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an ISO 20022 bank-to-customer statement ({@code camt.053}) of one account into statement lines (FIN-BK-003;
 * F5 plan decision D3).
 * <ul>
 *   <li>One {@code Stmt} per file: its account ({@code Acct/Id/IBAN} or {@code Acct/Id/Othr/Id}), its days
 *       ({@code FrToDt}, else the balances' dates) and its balances: {@code OPBD} (or {@code PRCD}) opening and
 *       {@code CLBD} closing, signed by {@code CdtDbtInd}; a previous closing balance ({@code PRCD}) is of the day
 *       before the statement.</li>
 *   <li>Each booked entry ({@code Ntry}; pending ones are not on the statement) is a line: amount signed by
 *       {@code CdtDbtInd}, day {@code BookgDt} else {@code ValDt} (a value date may lie outside the statement),
 *       reference {@code AcctSvcrRef}, description {@code AddtlNtryInf} else the remittance information, type the
 *       bank transaction code.</li>
 * </ul>
 * Only what a statement needs is kept, and no more than a statement can hold: a file that nests deeply, names
 * elements at length, or has many balances or values in one entry is refused. A statement without entries is
 * refused too: nothing to record, its balances carry over to the next.
 * The platform's own XML layout takes the first value of a path, and both balances share one; hence this parser.
 * Like it, it reads no DTD, no external entity and no entity reference.
 */
public final class Camt053Parser implements ImportParser {

    private static final XMLInputFactory FACTORY = factory();
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NAME = 64;
    private static final int MAX_BALANCES = 20;
    private static final int MAX_PART_VALUES = 200;
    /** The statement's own values that are read, by the end of their path. */
    private static final List<String> STATEMENT_PATHS = List.of("Stmt/Acct/Id/IBAN", "Stmt/Acct/Id/Othr/Id",
        "Stmt/Acct/Ccy", "Stmt/FrToDt/FrDtTm", "Stmt/FrToDt/ToDtTm");

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External resources are not read");
        });
        return factory;
    }

    @Override
    public void parse(Path file, ParseLimits limits, Sink sink) throws IOException {
        if (Files.size(file) > limits.maxUncompressedBytes()) {
            throw new ImportFileException("file", "The file is larger than " + limits.maxUncompressedBytes()
                + " bytes");
        }
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(in);
            try {
                new Reader(reader, limits).read().emit(sink);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new ImportFileException("file", "The file is not a well-formed XML document");
        }
    }

    /** A balance or an entry as read: element path below it to text. */
    private static final class Part {
        final Map<String, String> values = new LinkedHashMap<>();
        final String where;

        Part(String where) {
            this.where = where;
        }

        String get(String path) {
            return values.get(path);
        }
    }

    private static final class Reader {
        private final XMLStreamReader xml;
        private final ParseLimits limits;
        private final Deque<String> path = new ArrayDeque<>();
        private final List<Part> balances = new ArrayList<>();
        private final List<Part> entries = new ArrayList<>();
        private final Map<String, String> statement = new LinkedHashMap<>();
        private int statements;

        Reader(XMLStreamReader xml, ParseLimits limits) {
            this.xml = xml;
            this.limits = limits;
        }

        Reader read() throws XMLStreamException {
            Part part = null;
            int partDepth = 0;
            StringBuilder text = new StringBuilder();
            while (xml.hasNext()) {
                int event = xml.next();
                switch (event) {
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE,
                         XMLStreamConstants.ENTITY_DECLARATION ->
                        throw new ImportFileException("file", "Documents with a DTD or entity references are not read");
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = xml.getLocalName();
                        if (name.length() > MAX_NAME) {
                            throw new ImportFileException("file", "An element name is longer than " + MAX_NAME
                                + " characters");
                        }
                        path.addLast(name);
                        if (path.size() > MAX_DEPTH) {
                            throw new ImportFileException("file", "The document is nested too deeply");
                        }
                        text.setLength(0);
                        String joined = String.join("/", path);
                        if (joined.endsWith("BkToCstmrStmt/Stmt")) {
                            statements++;
                            if (statements > 1) {
                                throw new ImportFileException("file", "A statement file holds one statement");
                            }
                        } else if (part == null && (joined.endsWith("Stmt/Bal") || joined.endsWith("Stmt/Ntry"))) {
                            String where = name.equals("Ntry") ? "entry " + (entries.size() + 1)
                                : "balance " + (balances.size() + 1);
                            part = new Part(where);
                            partDepth = path.size();
                            if (name.equals("Ntry")) {
                                if (entries.size() >= limits.maxRecords()) {
                                    throw new ImportFileException(where, "The file has more than "
                                        + limits.maxRecords() + " entries");
                                }
                                entries.add(part);
                            } else {
                                if (balances.size() >= MAX_BALANCES) {
                                    throw new ImportFileException(where, "The statement has more than "
                                        + MAX_BALANCES + " balances");
                                }
                                balances.add(part);
                            }
                        }
                        if (part != null && "Amt".equals(name) && xml.getAttributeValue(null, "Ccy") != null) {
                            part.values.put(relative(partDepth) + "/@Ccy", xml.getAttributeValue(null, "Ccy"));
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        text.append(xml.getText());
                        if (text.length() > limits.maxCellLength()) {
                            throw new ImportFileException("file", "A value is longer than " + limits.maxCellLength()
                                + " characters");
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        String value = text.toString().strip();
                        if (part != null && path.size() > partDepth && !value.isEmpty()) {
                            if (part.values.size() >= MAX_PART_VALUES) {
                                throw new ImportFileException(part.where, "An entry holds more than "
                                    + MAX_PART_VALUES + " values");
                            }
                            part.values.putIfAbsent(relative(partDepth), value);
                        } else if (part == null && !value.isEmpty()) {
                            String joined = String.join("/", path);
                            for (String wanted : STATEMENT_PATHS) {
                                if (joined.endsWith(wanted)) {
                                    statement.putIfAbsent(wanted, value);
                                }
                            }
                        }
                        if (part != null && path.size() == partDepth) {
                            part = null;
                        }
                        path.removeLast();
                        text.setLength(0);
                    }
                    default -> { }
                }
            }
            if (statements != 1) {
                throw new ImportFileException("file", "A camt.053 file holds a BkToCstmrStmt/Stmt");
            }
            return this;
        }

        /** The path below the current part's element. */
        private String relative(int partDepth) {
            List<String> names = new ArrayList<>(path);
            return String.join("/", names.subList(partDepth, names.size()));
        }

        void emit(Sink sink) {
            String account = find("Stmt/Acct/Id/IBAN");
            if (account == null) {
                account = find("Stmt/Acct/Id/Othr/Id");
            }
            if (account == null) {
                throw new ImportFileException("file", "The statement names its account");
            }
            Part opening = balance("OPBD", "PRCD");
            Part closing = balance("CLBD");
            if (opening == null || closing == null) {
                throw new ImportFileException("file", "The statement gives its opening (OPBD) and closing (CLBD) "
                    + "balances");
            }
            LocalDate from = day(find("Stmt/FrToDt/FrDtTm"), "file");
            LocalDate to = day(find("Stmt/FrToDt/ToDtTm"), "file");
            if (from == null) {
                from = day(first(opening.get("Dt/Dt"), opening.get("Dt/DtTm")), opening.where);
                // The previous statement's closing is of its last day; this statement starts the day after.
                if (from != null && "PRCD".equals(first(opening.get("Tp/CdOrPrtry/Cd"), ""))) {
                    from = from.plusDays(1);
                }
            }
            if (to == null) {
                to = day(first(closing.get("Dt/Dt"), closing.get("Dt/DtTm")), closing.where);
            }
            if (from == null || to == null) {
                throw new ImportFileException("file", "The statement gives its days (FrToDt or the balances' dates)");
            }
            String currency = first(find("Stmt/Acct/Ccy"), opening.get("Amt/@Ccy"));
            BigDecimal open = signed(opening, opening.where);
            BigDecimal close = signed(closing, closing.where);
            sink.columns(StatementColumns.ALL);
            sink.header(StatementColumns.OPENING, open.toPlainString());
            sink.header(StatementColumns.CLOSING, close.toPlainString());
            sink.header(StatementColumns.FROM, from.toString());
            sink.header(StatementColumns.TO, to.toString());
            int number = 0;
            for (Part entry : entries) {
                String status = first(entry.get("Sts/Cd"), entry.get("Sts"));
                if (status != null && !"BOOK".equals(status)) {
                    continue;
                }
                LocalDate date = day(first(entry.get("BookgDt/Dt"), entry.get("BookgDt/DtTm"), entry.get("ValDt/Dt"),
                    entry.get("ValDt/DtTm")), entry.where);
                if (date == null) {
                    throw new ImportFileException(entry.where, "An entry gives its value or booking date");
                }
                Map<String, String> cells = new LinkedHashMap<>();
                cells.put(StatementColumns.DATE, date.toString());
                cells.put(StatementColumns.REFERENCE, first(entry.get("AcctSvcrRef"),
                    entry.get("NtryDtls/TxDtls/Refs/AcctSvcrRef"), ""));
                cells.put(StatementColumns.DESCRIPTION, first(entry.get("AddtlNtryInf"),
                    entry.get("NtryDtls/TxDtls/RmtInf/Ustrd"), entry.get("NtryDtls/TxDtls/AddtlTxInf"), ""));
                cells.put(StatementColumns.AMOUNT, signed(entry, entry.where).toPlainString());
                cells.put(StatementColumns.TYPE, first(entry.get("BkTxCd/Prtry/Cd"), typeCode(entry), ""));
                cells.put(StatementColumns.ACCOUNT, StatementCheck.last4(account));
                cells.put(StatementColumns.FROM, from.toString());
                cells.put(StatementColumns.TO, to.toString());
                cells.put(StatementColumns.OPENING, open.toPlainString());
                cells.put(StatementColumns.CLOSING, close.toPlainString());
                cells.put(StatementColumns.CURRENCY, currency == null ? "" : currency);
                number++;
                sink.record(new RawRecord(number, entry.where, cells));
            }
            if (number == 0) {
                throw new ImportFileException("file", "The statement has no booked entries: nothing to record, its "
                    + "balances carry over to the next");
            }
        }

        private String find(String suffix) {
            for (Map.Entry<String, String> e : statement.entrySet()) {
                if (e.getKey().endsWith(suffix)) {
                    return e.getValue();
                }
            }
            return null;
        }

        private Part balance(String... codes) {
            for (String code : codes) {
                for (Part balance : balances) {
                    if (code.equals(first(balance.get("Tp/CdOrPrtry/Cd"), balance.get("Tp/CdOrPrtry/Prtry")))) {
                        return balance;
                    }
                }
            }
            return null;
        }

        private static String typeCode(Part entry) {
            String domain = entry.get("BkTxCd/Domn/Cd");
            if (domain == null) {
                return null;
            }
            return domain + "/" + first(entry.get("BkTxCd/Domn/Fmly/Cd"), "") + "/"
                + first(entry.get("BkTxCd/Domn/Fmly/SubFmlyCd"), "");
        }

        private static BigDecimal signed(Part part, String where) {
            String amount = part.get("Amt");
            String indicator = part.get("CdtDbtInd");
            if (amount == null || !amount.matches("[0-9]{1,13}(\\.[0-9]{1,5})?")) {
                throw new ImportFileException(where, "An amount is a positive decimal number: " + amount);
            }
            if (!"CRDT".equals(indicator) && !"DBIT".equals(indicator)) {
                throw new ImportFileException(where, "CdtDbtInd is CRDT or DBIT: " + indicator);
            }
            BigDecimal value = new BigDecimal(amount);
            return "DBIT".equals(indicator) ? value.negate() : value;
        }

        private static LocalDate day(String text, String where) {
            if (text == null) {
                return null;
            }
            try {
                return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
            } catch (DateTimeParseException e) {
                throw new ImportFileException(where, "A date is an ISO date: " + text);
            }
        }

        private static String first(String... values) {
            for (String value : values) {
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }
}
