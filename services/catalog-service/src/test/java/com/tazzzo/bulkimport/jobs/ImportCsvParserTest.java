package com.tazzzo.bulkimport.jobs;

import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportCsvParserTest {

    private static List<ImportCsvParser.Row> parse(String csv) throws IOException {
        List<ImportCsvParser.Row> rows = new ArrayList<>();
        int n = ImportCsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), rows::add);
        assertThat(n).isEqualTo(rows.size());
        return rows;
    }

    @Test
    void the_cms_wizard_vocabulary_maps_to_the_single_create_request() throws IOException {
        List<ImportCsvParser.Row> rows = parse("""
                Product ID,Name,Brand,Barcode,Market,Vertical,Release,Classification,attr.pack_size,attr.pack_unit,attr.organic
                tzp-c-001, Basmati rice 5 kg ,amul,8901234567890,,TZV-000001,0.9.0,,5,kg,true
                TZP-C-002,"Rice, broken",BR,,in,TZV-000001,0.9.0,confirmed,2.5,kg,
                """);
        assertThat(rows).hasSize(2);
        CreateProductRequest a = rows.get(0).request();
        assertThat(rows.get(0).line()).isEqualTo(1);
        assertThat(a.id()).as("the product id is NOT case-normalised").isEqualTo("tzp-c-001");
        assertThat(a.title()).isEqualTo("Basmati rice 5 kg");
        assertThat(a.brandCode()).isEqualTo("AMUL");
        assertThat(a.identityType()).isEqualTo("gtin");
        assertThat(a.internalKey()).isNull();
        assertThat(a.gtins()).hasSize(1);
        assertThat(a.gtins().get(0).value()).isEqualTo("8901234567890");
        assertThat(a.gtins().get(0).market()).as("market defaults to IN").isEqualTo("IN");
        assertThat(a.classificationStatus()).as("status defaults to provisional").isEqualTo("provisional");
        assertThat(a.productType()).isEqualTo("single");
        assertThat(a.attributes()).containsEntry("pack_size", 5).containsEntry("pack_unit", "kg").containsEntry("organic", true);
        assertThat(a.evidenceRefs()).isEmpty();
        CreateProductRequest b = rows.get(1).request();
        assertThat(b.title()).as("a quoted cell keeps its comma").isEqualTo("Rice, broken");
        assertThat(b.identityType()).isEqualTo("internal");
        assertThat(b.gtins()).isNull();
        assertThat(b.classificationStatus()).isEqualTo("confirmed");
        assertThat(b.attributes()).containsEntry("pack_size", 2.5).doesNotContainKey("organic");
    }

    @Test
    void product_ids_keep_their_case_and_ids_differing_only_in_case_are_distinct_rows() throws IOException {
        List<ImportCsvParser.Row> rows = parse("id,title,brand,vertical,release\n"
                + "TZP-med-3,x,br,TZV-000001,0.9.0\n"
                + "TZP-a,y,BR,TZV-000001,0.9.0\n"
                + "TZP-A,z,BR,TZV-000001,0.9.0\n");
        assertThat(rows.get(0).request().id()).isEqualTo("TZP-med-3");
        assertThat(rows.get(0).request().brandCode()).as("only the brand is upper-cased").isEqualTo("BR");
        assertThat(rows.get(1).request().id()).isEqualTo("TZP-a");
        assertThat(rows.get(2).request().id()).isEqualTo("TZP-A");
        assertThat(ImportJobService.dedupKey(rows.get(1).request())).as("dedup is case-sensitive")
                .isNotEqualTo(ImportJobService.dedupKey(rows.get(2).request()));
    }

    @Test
    void errors_csv_cells_that_a_spreadsheet_would_read_as_formulas_are_neutralised() {
        assertThat(ImportJobService.csv("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(ImportJobService.csv("+1")).isEqualTo("'+1");
        assertThat(ImportJobService.csv("-2+3")).isEqualTo("'-2+3");
        assertThat(ImportJobService.csv("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ImportJobService.csv("\tcmd")).isEqualTo("'\tcmd");
        assertThat(ImportJobService.csv("\rcmd")).isEqualTo("\"'\rcmd\"");
        assertThat(ImportJobService.csv("TZP-1")).isEqualTo("TZP-1");
        assertThat(ImportJobService.csv("a=b, c")).isEqualTo("\"a=b, c\"");
        assertThat(ImportJobService.csv(null)).isEmpty();
        assertThat(ImportJobService.csv("")).isEmpty();
    }

    @Test
    void internal_identity_carries_the_key_and_quotes_newlines_and_doubled_quotes_are_rfc4180() throws IOException {
        List<ImportCsvParser.Row> rows = parse("id,title,brand,vertical,release,key\r\n"
                + "TZP-1,\"Line one\nline \"\"two\"\"\",BR,TZV-000001,0.9.0,k|1\r\n\r\n"
                + "TZP-2,plain,BR,TZV-000001,0.9.0,\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).request().title()).isEqualTo("Line one\nline \"two\"");
        assertThat(rows.get(0).request().internalKey()).isEqualTo("k|1");
        assertThat(rows.get(1).request().internalKey()).as("an empty key is absent, so validation reports it").isNull();
        assertThat(rows.get(1).line()).as("blank lines are skipped and not counted").isEqualTo(2);
    }

    @Test
    void a_file_without_the_required_columns_or_with_broken_quoting_is_refused_as_a_file() {
        assertThatThrownBy(() -> parse("id,title\nTZP-1,x\n"))
                .isInstanceOf(ImportCsvParser.ImportFileException.class)
                .hasMessageContaining("brandCode").hasMessageContaining("verticalId").hasMessageContaining("releaseId");
        assertThatThrownBy(() -> parse("id,title,brand,vertical,release\nTZP-1,\"open quote,BR,V,R\n"))
                .isInstanceOf(ImportCsvParser.ImportFileException.class).hasMessageContaining("quoted cell");
        assertThatThrownBy(() -> parse("id,title,brand,vertical,release\nTZP-1,ab\"c,BR,V,R\n"))
                .isInstanceOf(ImportCsvParser.ImportFileException.class).hasMessageContaining("quote");
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(ImportCsvParser.ImportFileException.class).hasMessageContaining("no header");
        assertThatThrownBy(() -> parse("id,title,brand,vertical,release\nTZP-1," + "x".repeat(4_001) + ",BR,V,R\n"))
                .isInstanceOf(ImportCsvParser.ImportFileException.class).hasMessageContaining("4000");
    }

    @Test
    void a_header_only_file_has_zero_rows_and_a_short_row_reads_missing_cells_as_empty() throws IOException {
        assertThat(parse("id,title,brand,vertical,release\n")).isEmpty();
        List<ImportCsvParser.Row> rows = parse("id,title,brand,vertical,release\nTZP-1,x\n");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).request().brandCode()).isEmpty();
        assertThat(rows.get(0).request().verticalId()).isEmpty();
    }

    @Test
    void a_utf8_bom_is_tolerated_and_a_data_row_cannot_exceed_the_column_cap() throws IOException {
        List<ImportCsvParser.Row> rows = parse("\uFEFFattr.organic,id,title,brand,vertical,release\ntrue,TZP-1,x,BR,TZV-000001,0.9.0\n");
        assertThat(rows.get(0).request().attributes()).as("the BOM does not hide the first column").containsEntry("organic", true);
        assertThat(rows.get(0).request().id()).isEqualTo("TZP-1");
        assertThatThrownBy(() -> parse("id,title,brand,vertical,release\nTZP-1,x,BR,V,R" + ",".repeat(70) + "\n"))
                .isInstanceOf(ImportCsvParser.ImportFileException.class).hasMessageContaining("64 columns");
    }

    @Test
    void product_id_whitespace_is_not_normalised_so_the_id_grammar_rejects_it_like_the_json_path() throws IOException {
        List<ImportCsvParser.Row> rows = parse("id,title,brand,vertical,release\r\n"
                + "\"TZP-1\n\",x,BR,V,R\r\n"          // trailing LF inside a quoted cell
                + "\"TZP-\n2\",x,BR,V,R\r\n"           // embedded LF
                + "\" TZP-3\",x,BR,V,R\r\n"             // surrounding space
                + "TZP-4,x,BR,V,R\r\n"                    // CRLF outside quotes still works
                + "TZP-Mixed-5,x,BR,V,R\r\n");
        assertThat(rows.get(0).request().id()).isEqualTo("TZP-1\n");
        assertThat(rows.get(1).request().id()).isEqualTo("TZP-\n2");
        assertThat(rows.get(2).request().id()).isEqualTo(" TZP-3");
        assertThat(rows).extracting(r -> com.tazzzo.catalog.domain.ProductIds.isValid(r.request().id()))
                .containsExactly(false, false, false, true, true);
        assertThat(rows.get(3).request().id()).isEqualTo("TZP-4");
        assertThat(rows.get(4).request().id()).as("no case change").isEqualTo("TZP-Mixed-5");
    }

    @Test
    void typed_cells_match_what_the_json_row_would_carry() {
        assertThat(ImportCsvParser.typed("5")).isEqualTo(5);
        assertThat(ImportCsvParser.typed("3000000000")).isEqualTo(3_000_000_000L);
        assertThat(ImportCsvParser.typed("-2.50")).isEqualTo(-2.5);
        assertThat(ImportCsvParser.typed("TRUE")).isEqualTo(true);
        assertThat(ImportCsvParser.typed("5 kg")).isEqualTo("5 kg");
        assertThat(ImportCsvParser.typed("1e3")).isEqualTo("1e3");
        assertThat(ImportCsvParser.typed("1234567890123456")).as("beyond 15 digits stays text").isEqualTo("1234567890123456");
    }
}
