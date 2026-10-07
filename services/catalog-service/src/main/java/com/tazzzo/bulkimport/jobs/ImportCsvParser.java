package com.tazzzo.bulkimport.jobs;

import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.api.ApiDtos.GtinDto;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A streaming RFC 4180 CSV reader for product import files: one row at a time, never the whole file in memory, with
 * the SAME column vocabulary and row shape the CMS import wizard produces client-side (`lib/imports.ts`): a header row
 * whose cells match known aliases (case- and punctuation-insensitive), rows mapped to the single-create request.
 *
 * <p>Columns (aliases): {@code id} (productid, tzpid, sku, skuid) · {@code title} (name, productname) · {@code brandCode}
 * (brand) · {@code gtin} (barcode, ean, upc) · {@code market} (gtinmarket, country; default IN) · {@code internalKey}
 * (key) · {@code verticalId} (vertical) · {@code releaseId} (release, taxonomyrelease) · {@code classificationStatus}
 * (classification, status; default provisional) · any {@code attr.<name>} column becomes a string attribute. Identity is
 * {@code gtin} when a GTIN is present, else {@code internal}. Cell values are trimmed; id and brand are upper-cased, as
 * the CMS does. A quoted cell may contain commas, newlines and doubled quotes.
 */
public final class ImportCsvParser {

    /** A parsed data row with its 1-based line number (the header is line 0, as a spreadsheet user counts). */
    public record Row(int line, CreateProductRequest request) { }

    static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("id", List.of("id", "productid", "tzpid", "sku", "skuid"));
        ALIASES.put("title", List.of("title", "name", "productname"));
        ALIASES.put("brandCode", List.of("brand", "brandcode"));
        ALIASES.put("gtin", List.of("gtin", "barcode", "ean", "upc"));
        ALIASES.put("market", List.of("market", "gtinmarket", "country"));
        ALIASES.put("internalKey", List.of("internalkey", "key"));
        ALIASES.put("verticalId", List.of("vertical", "verticalid"));
        ALIASES.put("releaseId", List.of("release", "releaseid", "taxonomyrelease"));
        ALIASES.put("classificationStatus", List.of("classification", "classificationstatus", "status"));
    }
    static final List<String> REQUIRED = List.of("id", "title", "brandCode", "verticalId", "releaseId");
    static final int MAX_CELL = 4_000;
    static final int MAX_COLUMNS = 64;

    private ImportCsvParser() { }

    /** Column name → index, from the header row; {@code attr.*} columns are kept under their full lower-cased name. */
    static Map<String, Integer> mapHeader(List<String> header) {
        if (header.size() > MAX_COLUMNS) throw new ImportFileException("too many columns (max " + MAX_COLUMNS + ")");
        Map<String, Integer> map = new LinkedHashMap<>();
        boolean[] used = new boolean[header.size()];
        for (Map.Entry<String, List<String>> field : ALIASES.entrySet()) {
            for (int i = 0; i < header.size(); i++) {
                if (!used[i] && field.getValue().contains(norm(header.get(i)))) {
                    map.put(field.getKey(), i);
                    used[i] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT);
            if (!used[i] && h.startsWith("attr.") && h.length() > 5) {
                map.put(h, i);
                used[i] = true;
            }
        }
        List<String> missing = new ArrayList<>();
        for (String r : REQUIRED) if (!map.containsKey(r)) missing.add(r);
        if (!missing.isEmpty()) throw new ImportFileException("header is missing required columns: " + String.join(", ", missing));
        return map;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Builds the single-create request the CMS wizard would have built for this row. */
    static CreateProductRequest toRequest(List<String> cells, Map<String, Integer> map) {
        String id = cell(cells, map, "id").toUpperCase(Locale.ROOT);
        String gtin = cell(cells, map, "gtin");
        String internalKey = cell(cells, map, "internalKey");
        String market = cell(cells, map, "market");
        String status = cell(cells, map, "classificationStatus");
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getKey().startsWith("attr.")) {
                String v = e.getValue() < cells.size() ? cells.get(e.getValue()).trim() : "";
                if (!v.isEmpty()) attributes.put(e.getKey().substring(5), typed(v));
            }
        }
        return new CreateProductRequest(id, "single", gtin.isEmpty() ? "internal" : "gtin",
                gtin.isEmpty() ? (internalKey.isEmpty() ? null : internalKey) : null,
                gtin.isEmpty() ? null : List.of(new GtinDto(gtin, market.isEmpty() ? "IN" : market.toUpperCase(Locale.ROOT))),
                cell(cells, map, "brandCode").toUpperCase(Locale.ROOT), cell(cells, map, "title"), cell(cells, map, "verticalId"),
                cell(cells, map, "releaseId"), status.isEmpty() ? "provisional" : status, attributes, List.of(), null, null);
    }

    /**
     * A CSV cell is text; the governed attribute schemas type their values (a pack size is a number, a flag a boolean), so a
     * cell that reads as an integer, a decimal or a boolean is carried as that type, exactly as the JSON row would carry it
     * (an int where it fits, so a CSV re-run of a JSON-loaded product compares UNCHANGED, not CONFLICT).
     * Anything else stays a string. A value that must stay text but looks numeric is not representable in CSV; use JSON rows.
     */
    static Object typed(String v) {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return Boolean.parseBoolean(v);
        if (v.matches("-?\\d{1,15}")) {
            long n = Long.parseLong(v);
            return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) (int) n : (Object) n;   // as Jackson types a JSON row
        }
        if (v.matches("-?\\d{1,15}\\.\\d{1,10}")) return Double.parseDouble(v);
        return v;
    }

    private static String cell(List<String> cells, Map<String, Integer> map, String key) {
        Integer i = map.get(key);
        return i == null || i >= cells.size() ? "" : cells.get(i).trim();
    }

    /**
     * Streams {@code in} (UTF-8) and hands every data row to {@code sink} as soon as it is complete. Returns the number
     * of data rows. A malformed file (unbalanced quote, oversize cell, missing header) is an {@link ImportFileException}.
     */
    public static int parse(InputStream in, Consumer<Row> sink) throws IOException {
        Reader reader = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, Integer> map = null;
        int line = 0;
        List<String> record;
        while ((record = readRecord(reader)) != null) {
            if (map == null) {
                if (record.size() == 1 && record.get(0).isBlank()) continue;   // leading blank line
                map = mapHeader(record);
                continue;
            }
            if (record.size() == 1 && record.get(0).isBlank()) continue;       // blank data line
            line++;
            sink.accept(new Row(line, toRequest(record, map)));
        }
        if (map == null) throw new ImportFileException("the file has no header row");
        return line;
    }

    /** One RFC 4180 record (handles quoted cells with commas, CRLF/LF, doubled quotes); null at end of input. */
    static List<String> readRecord(Reader r) throws IOException {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int c;
        while ((c = r.read()) != -1) {
            any = true;
            if (quoted) {
                if (c == '"') {
                    r.mark(1);
                    int next = r.read();
                    if (next == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) r.reset();
                    }
                } else {
                    append(cell, c);
                }
            } else if (c == '"') {
                if (cell.length() != 0) throw new ImportFileException("a quote may only open a cell");
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    r.mark(1);
                    if (r.read() != '\n') r.reset();
                }
                cells.add(cell.toString());
                return cells;
            } else {
                append(cell, c);
            }
        }
        if (quoted) throw new ImportFileException("the file ends inside a quoted cell");
        if (!any) return null;
        cells.add(cell.toString());
        return cells;
    }

    private static void append(StringBuilder cell, int c) {
        if (cell.length() >= MAX_CELL) throw new ImportFileException("a cell exceeds " + MAX_CELL + " characters");
        cell.append((char) c);
    }

    /** A file the reader cannot accept at all (as opposed to a row that fails validation). */
    public static final class ImportFileException extends RuntimeException {
        public ImportFileException(String message) {
            super(message);
        }
    }
}
