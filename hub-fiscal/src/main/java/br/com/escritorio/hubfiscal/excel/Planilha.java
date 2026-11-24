package br.com.escritorio.hubfiscal.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

/**
 * Construtor simples de planilhas .xlsx (Apache POI, modo streaming para
 * volumes grandes), com estilos padronizados do hub: cabeçalho destacado,
 * filtros, painel congelado, moeda, e cores por status.
 */
public class Planilha implements AutoCloseable {

    /** Definição de coluna. */
    public record Coluna(String titulo, int largura, Tipo tipo) {
        public static Coluna texto(String t, int l) {
            return new Coluna(t, l, Tipo.TEXTO);
        }

        public static Coluna moeda(String t) {
            return new Coluna(t, 14, Tipo.MOEDA);
        }

        public static Coluna numero(String t) {
            return new Coluna(t, 12, Tipo.NUMERO);
        }

        public static Coluna percentual(String t) {
            return new Coluna(t, 11, Tipo.PERCENTUAL);
        }
    }

    public enum Tipo {
        TEXTO, MOEDA, NUMERO, PERCENTUAL
    }

    private final SXSSFWorkbook wb = new SXSSFWorkbook(200);
    private final CellStyle cabecalho;
    private final CellStyle titulo;
    private final CellStyle negrito;
    private final CellStyle textoQuebra;
    private final CellStyle moeda;
    private final CellStyle numero;
    private final CellStyle percentual;
    private final Map<String, CellStyle> estilosStatus = new HashMap<>();

    public Planilha() {
        Font fCab = wb.createFont();
        fCab.setBold(true);
        fCab.setColor(IndexedColors.WHITE.getIndex());
        XSSFCellStyle cab = (XSSFCellStyle) wb.createCellStyle();
        cab.setFont(fCab);
        cab.setFillForegroundColor(new XSSFColor(new byte[] { (byte) 0x1F, (byte) 0x3A, (byte) 0x5F }, null));
        cab.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cab.setVerticalAlignment(VerticalAlignment.CENTER);
        cab.setWrapText(true);
        cab.setBorderBottom(BorderStyle.THIN);
        cabecalho = cab;

        Font fTit = wb.createFont();
        fTit.setBold(true);
        fTit.setFontHeightInPoints((short) 14);
        titulo = wb.createCellStyle();
        titulo.setFont(fTit);

        Font fNeg = wb.createFont();
        fNeg.setBold(true);
        negrito = wb.createCellStyle();
        negrito.setFont(fNeg);

        textoQuebra = wb.createCellStyle();
        textoQuebra.setWrapText(true);
        textoQuebra.setVerticalAlignment(VerticalAlignment.TOP);

        short fmtMoeda = wb.createDataFormat().getFormat("#,##0.00");
        moeda = wb.createCellStyle();
        moeda.setDataFormat(fmtMoeda);
        numero = wb.createCellStyle();
        numero.setDataFormat(wb.createDataFormat().getFormat("#,##0.####"));
        percentual = wb.createCellStyle();
        percentual.setDataFormat(wb.createDataFormat().getFormat("0.00##"));
        percentual.setAlignment(HorizontalAlignment.RIGHT);
    }

    /** Cor de fundo (RGB hex) usada para uma palavra de status. */
    public void corStatus(String status, String rgbHex) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(hex(rgbHex), null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        estilosStatus.put(status, s);
    }

    private static byte[] hex(String h) {
        String s = h.replace("#", "");
        return new byte[] { (byte) Integer.parseInt(s.substring(0, 2), 16), (byte) Integer.parseInt(s.substring(2, 4), 16),
                (byte) Integer.parseInt(s.substring(4, 6), 16) };
    }

    /** Aba de resumo em formato chave/valor. */
    public Sheet abaResumo(String nome, String tituloTexto, List<String[]> pares) {
        Sheet sh = wb.createSheet(nome);
        sh.setColumnWidth(0, 45 * 256);
        sh.setColumnWidth(1, 90 * 256);
        Row r0 = sh.createRow(0);
        Cell c0 = r0.createCell(0);
        c0.setCellValue(tituloTexto);
        c0.setCellStyle(titulo);
        int i = 2;
        for (String[] p : pares) {
            Row r = sh.createRow(i++);
            if (p.length == 1) { // subtítulo
                Cell c = r.createCell(0);
                c.setCellValue(p[0]);
                c.setCellStyle(negrito);
                continue;
            }
            Cell a = r.createCell(0);
            a.setCellValue(p[0]);
            a.setCellStyle(negrito);
            Cell b = r.createCell(1);
            b.setCellValue(p[1] == null ? "" : p[1]);
            b.setCellStyle(textoQuebra);
        }
        return sh;
    }

    /**
     * Aba tabular. {@code colunaStatus} indica a coluna (índice) colorida pelo
     * status, ou -1.
     */
    public void abaTabela(String nome, List<Coluna> colunas, List<Object[]> linhas, int colunaStatus) {
        SXSSFSheet sh = wb.createSheet(nome);
        Row cab = sh.createRow(0);
        cab.setHeightInPoints(32);
        for (int i = 0; i < colunas.size(); i++) {
            Cell c = cab.createCell(i);
            c.setCellValue(colunas.get(i).titulo());
            c.setCellStyle(cabecalho);
            sh.setColumnWidth(i, Math.min(255, colunas.get(i).largura()) * 256);
        }
        int r = 1;
        for (Object[] linha : linhas) {
            Row row = sh.createRow(r++);
            for (int i = 0; i < colunas.size() && i < linha.length; i++) {
                Object v = linha[i];
                if (v == null) {
                    continue;
                }
                Cell c = row.createCell(i);
                Tipo t = colunas.get(i).tipo();
                if (v instanceof BigDecimal b) {
                    c.setCellValue(b.doubleValue());
                    c.setCellStyle(t == Tipo.MOEDA ? moeda : t == Tipo.PERCENTUAL ? percentual : numero);
                } else if (v instanceof Number n) {
                    c.setCellValue(n.doubleValue());
                    c.setCellStyle(t == Tipo.MOEDA ? moeda : t == Tipo.PERCENTUAL ? percentual : numero);
                } else {
                    String s = String.valueOf(v);
                    if (t != Tipo.TEXTO) {
                        try {
                            c.setCellValue(new BigDecimal(s).doubleValue());
                            c.setCellStyle(t == Tipo.MOEDA ? moeda : t == Tipo.PERCENTUAL ? percentual : numero);
                            continue;
                        } catch (NumberFormatException e) {
                            // cai para texto
                        }
                    }
                    c.setCellValue(s.length() > 32000 ? s.substring(0, 32000) : s);
                    if (i == colunaStatus && estilosStatus.containsKey(s)) {
                        c.setCellStyle(estilosStatus.get(s));
                    }
                }
            }
        }
        sh.createFreezePane(colunaStatus >= 0 ? colunaStatus + 1 : 0, 1);
        if (!colunas.isEmpty()) {
            sh.setAutoFilter(new CellRangeAddress(0, Math.max(r - 1, 1), 0, colunas.size() - 1));
        }
    }

    public byte[] bytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            wb.close();
        } catch (IOException e) {
            // ignora
        }
    }
}
