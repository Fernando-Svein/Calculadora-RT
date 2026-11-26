package br.com.escritorio.hubfiscal.relatorio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Relatório em PDF de uma simulação: cabeçalho (logo do escritório, título, data), empresa simulada, resumo em
 * pares rótulo/valor, tabela com quebra de página (cabeçalho repetido) e observações. A4 paisagem.
 * Usa as fontes padrão do PDF (Helvetica, codificação WinAnsi, que cobre o português); caracteres fora dela
 * são trocados por equivalentes simples.
 */
public final class RelatorioPdf {

    /** Coluna da tabela: tipo = texto, moeda, numero ou percentual; largura = peso relativo (opcional). */
    public record Coluna(String titulo, String tipo, Integer largura) {
    }

    public record Dados(String titulo, String geradoEm, String geradoPor, List<String> empresa, List<List<String>> resumo,
            List<Coluna> colunas, List<List<Object>> linhas, List<String> observacoes, byte[] logo) {
    }

    private static final PDRectangle PAGINA = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGEM = 36;
    private static final float LARGURA_UTIL = PAGINA.getWidth() - 2 * MARGEM;
    private static final float RODAPE = 28;
    private static final float[] AZUL = { 0.12f, 0.23f, 0.37f };
    private static final float[] CINZA_CLARO = { 0.93f, 0.94f, 0.96f };
    private static final float[] CINZA_LINHA = { 0.80f, 0.82f, 0.86f };
    private static final float[] TEXTO_SUAVE = { 0.35f, 0.38f, 0.43f };

    private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont negrito = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final Map<Character, String> trocas = new HashMap<>();

    private final PDDocument doc = new PDDocument();
    private PDPageContentStream cs;
    private float y;

    private RelatorioPdf() {
    }

    static byte[] gerar(Dados d) {
        RelatorioPdf r = new RelatorioPdf();
        try {
            return r.montar(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] montar(Dados d) throws IOException {
        try (doc) {
            novaPagina();
            cabecalho(d);
            caixaEmpresa(d.empresa());
            resumo(d.resumo());
            if (d.colunas() != null && !d.colunas().isEmpty()) {
                tabela(d.colunas(), d.linhas() == null ? List.of() : d.linhas());
            }
            observacoes(d.observacoes());
            cs.close();
            rodapes(d.titulo());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ blocos

    private void cabecalho(Dados d) throws IOException {
        float topo = y;
        float xTitulo = MARGEM;
        if (d.logo() != null && d.logo().length > 0) {
            try {
                PDImageXObject img = PDImageXObject.createFromByteArray(doc, d.logo(), "logo");
                float h = 34;
                float w = Math.min(140, img.getWidth() * h / img.getHeight());
                h = w * img.getHeight() / img.getWidth();
                cs.drawImage(img, MARGEM, topo - h, w, h);
                xTitulo = MARGEM + w + 14;
            } catch (IOException | RuntimeException e) {
                // logo ilegível: segue sem ela
            }
        }
        texto("HUB Fiscal", normal, 8.5f, xTitulo, topo - 9, TEXTO_SUAVE);
        texto(d.titulo(), negrito, 15, xTitulo, topo - 27, AZUL);
        String gerado = "Gerado em " + d.geradoEm() + (d.geradoPor() == null ? "" : " por " + d.geradoPor());
        float wg = largura(gerado, normal, 8.5f);
        texto(gerado, normal, 8.5f, MARGEM + LARGURA_UTIL - wg, topo - 9, TEXTO_SUAVE);
        y = topo - 44;
        linhaHorizontal(y, AZUL, 1.2f);
        y -= 12;
    }

    private void caixaEmpresa(List<String> empresa) throws IOException {
        List<String> origem = empresa == null || empresa.isEmpty()
                ? List.of("Simulação sem empresa selecionada: valores genéricos informados na tela.")
                : empresa;
        // a primeira linha (nome) em negrito; as demais quebram em várias linhas se forem longas
        List<String> linhas = new ArrayList<>();
        List<Boolean> negritos = new ArrayList<>();
        for (int i = 0; i < origem.size(); i++) {
            PDFont f = i == 0 ? negrito : normal;
            for (String parte : quebrar(origem.get(i), f, 9.5f, LARGURA_UTIL - 16)) {
                linhas.add(parte);
                negritos.add(i == 0);
            }
        }
        float altura = 16 + 12 * linhas.size();
        garantirEspaco(altura + 8);
        cs.setNonStrokingColor(CINZA_CLARO[0], CINZA_CLARO[1], CINZA_CLARO[2]);
        cs.addRect(MARGEM, y - altura, LARGURA_UTIL, altura);
        cs.fill();
        texto("EMPRESA DA SIMULAÇÃO", negrito, 7.5f, MARGEM + 8, y - 11, TEXTO_SUAVE);
        float yl = y - 24;
        for (int i = 0; i < linhas.size(); i++) {
            texto(linhas.get(i), negritos.get(i) ? negrito : normal, 9.5f, MARGEM + 8, yl, null);
            yl -= 12;
        }
        y -= altura + 12;
    }

    private void resumo(List<List<String>> pares) throws IOException {
        if (pares == null || pares.isEmpty()) {
            return;
        }
        garantirEspaco(30);
        texto("Resumo", negrito, 11, MARGEM, y - 10, AZUL);
        y -= 18;
        float colRotulo = 190;
        for (List<String> par : pares) {
            if (par == null || par.isEmpty()) {
                continue;
            }
            if (par.size() == 1) { // subtítulo
                garantirEspaco(18);
                y -= 4;
                texto(par.get(0), negrito, 9.5f, MARGEM, y - 9, null);
                y -= 14;
                continue;
            }
            List<String> valor = quebrar(par.get(1), normal, 9, LARGURA_UTIL - colRotulo);
            float h = 12 * valor.size();
            garantirEspaco(h + 2);
            texto(cortar(par.get(0), negrito, 9, colRotulo - 8), negrito, 9, MARGEM, y - 9, TEXTO_SUAVE);
            float yl = y - 9;
            for (String v : valor) {
                texto(v, normal, 9, MARGEM + colRotulo, yl, null);
                yl -= 12;
            }
            y -= h + 2;
        }
        y -= 10;
    }

    private void tabela(List<Coluna> colunas, List<List<Object>> linhas) throws IOException {
        float tam = colunas.size() > 12 ? 6.8f : colunas.size() > 8 ? 7.5f : 8.5f;
        float entre = tam + 2.5f;
        float[] larguras = larguras(colunas, tam);
        garantirEspaco(40);
        texto("Detalhamento", negrito, 11, MARGEM, y - 10, AZUL);
        y -= 18;
        cabecalhoTabela(colunas, larguras, tam, entre);
        boolean zebra = false;
        for (List<Object> linha : linhas) {
            List<List<String>> celulas = new ArrayList<>();
            int maxLinhas = 1;
            for (int i = 0; i < colunas.size(); i++) {
                Object v = linha != null && i < linha.size() ? linha.get(i) : null;
                List<String> partes = quebrar(formatar(v, colunas.get(i).tipo()), normal, tam, larguras[i] - 6);
                if (partes.size() > 6) { // célula enorme (ex.: lista de erros): limita a 6 linhas
                    partes = new ArrayList<>(partes.subList(0, 6));
                    partes.set(5, cortar(partes.get(5) + "…", normal, tam, larguras[i] - 6));
                }
                celulas.add(partes);
                maxLinhas = Math.max(maxLinhas, partes.size());
            }
            float h = maxLinhas * entre + 5;
            if (y - h < MARGEM + RODAPE) {
                novaPagina();
                cabecalhoTabela(colunas, larguras, tam, entre);
            }
            if (zebra) {
                cs.setNonStrokingColor(0.975f, 0.978f, 0.985f);
                cs.addRect(MARGEM, y - h, LARGURA_UTIL, h);
                cs.fill();
            }
            zebra = !zebra;
            float x = MARGEM;
            for (int i = 0; i < colunas.size(); i++) {
                boolean direita = !"texto".equals(tipo(colunas.get(i).tipo()));
                float yl = y - tam - 2;
                for (String parte : celulas.get(i)) {
                    float px = direita ? x + larguras[i] - 3 - largura(parte, normal, tam) : x + 3;
                    texto(parte, normal, tam, px, yl, null);
                    yl -= entre;
                }
                x += larguras[i];
            }
            y -= h;
            linhaHorizontal(y, CINZA_LINHA, 0.4f);
        }
        if (linhas.isEmpty()) {
            texto("Nenhuma linha.", normal, 9, MARGEM + 3, y - 11, TEXTO_SUAVE);
            y -= 16;
        }
        y -= 10;
    }

    private void cabecalhoTabela(List<Coluna> colunas, float[] larguras, float tam, float entre) throws IOException {
        List<List<String>> titulos = new ArrayList<>();
        int max = 1;
        for (int i = 0; i < colunas.size(); i++) {
            List<String> t = quebrar(colunas.get(i).titulo(), negrito, tam, larguras[i] - 6);
            titulos.add(t);
            max = Math.max(max, t.size());
        }
        float h = max * entre + 6;
        garantirEspaco(h + entre * 2);
        cs.setNonStrokingColor(AZUL[0], AZUL[1], AZUL[2]);
        cs.addRect(MARGEM, y - h, LARGURA_UTIL, h);
        cs.fill();
        float x = MARGEM;
        for (int i = 0; i < colunas.size(); i++) {
            boolean direita = !"texto".equals(tipo(colunas.get(i).tipo()));
            float yl = y - tam - 3;
            for (String parte : titulos.get(i)) {
                float px = direita ? x + larguras[i] - 3 - largura(parte, negrito, tam) : x + 3;
                texto(parte, negrito, tam, px, yl, new float[] { 1, 1, 1 });
                yl -= entre;
            }
            x += larguras[i];
        }
        y -= h;
    }

    private void observacoes(List<String> obs) throws IOException {
        if (obs == null || obs.isEmpty()) {
            return;
        }
        garantirEspaco(30);
        texto("Observações", negrito, 11, MARGEM, y - 10, AZUL);
        y -= 18;
        for (String o : obs) {
            if (o == null || o.isBlank()) {
                continue;
            }
            List<String> partes = quebrar(o, normal, 8.5f, LARGURA_UTIL - 12);
            garantirEspaco(11 * partes.size() + 3);
            texto("•", normal, 8.5f, MARGEM, y - 9, null);
            for (String p : partes) {
                texto(p, normal, 8.5f, MARGEM + 10, y - 9, null);
                y -= 11;
            }
            y -= 3;
        }
    }

    private void rodapes(String titulo) throws IOException {
        int total = doc.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage p = doc.getPage(i);
            try (PDPageContentStream c = new PDPageContentStream(doc, p, AppendMode.APPEND, true, true)) {
                cs = c;
                linhaHorizontal(MARGEM + 12, CINZA_LINHA, 0.5f);
                texto(cortar("HUB Fiscal · " + titulo + " · Simulação para conferência: não substitui o documento fiscal",
                        normal, 7.5f, LARGURA_UTIL - 80), normal, 7.5f, MARGEM, MARGEM, TEXTO_SUAVE);
                String pg = "Página " + (i + 1) + " de " + total;
                texto(pg, normal, 7.5f, MARGEM + LARGURA_UTIL - largura(pg, normal, 7.5f), MARGEM, TEXTO_SUAVE);
            }
        }
    }

    // ------------------------------------------------------------------ utilitários de layout

    private void novaPagina() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage p = new PDPage(PAGINA);
        doc.addPage(p);
        cs = new PDPageContentStream(doc, p);
        y = PAGINA.getHeight() - MARGEM;
    }

    private void garantirEspaco(float altura) throws IOException {
        if (y - altura < MARGEM + RODAPE) {
            novaPagina();
        }
    }

    /** Larguras proporcionais ao peso informado (ou ao tamanho do título), ajustadas à largura útil. */
    private float[] larguras(List<Coluna> colunas, float tam) throws IOException {
        float[] w = new float[colunas.size()];
        float soma = 0;
        for (int i = 0; i < w.length; i++) {
            Coluna c = colunas.get(i);
            float peso = c.largura() != null && c.largura() > 0 ? Math.min(c.largura(), 80)
                    : Math.max(8, Math.min(28, (c.titulo() == null ? 0 : c.titulo().length()) + 2));
            if (!"texto".equals(tipo(c.tipo()))) {
                peso = Math.max(peso, 11);
            }
            w[i] = peso;
            soma += peso;
        }
        for (int i = 0; i < w.length; i++) {
            w[i] = w[i] / soma * LARGURA_UTIL;
        }
        // nenhuma coluna mais estreita que a palavra mais longa do título (evita quebrar "NCM/NBS" no meio);
        // a diferença sai das colunas que têm folga
        float[] minimo = new float[w.length];
        float falta = 0;
        float folga = 0;
        for (int i = 0; i < w.length; i++) {
            String t = colunas.get(i).titulo() == null ? "" : colunas.get(i).titulo();
            for (String p : t.split("\\s+")) {
                minimo[i] = Math.max(minimo[i], largura(p, negrito, tam) + 7);
            }
            falta += Math.max(0, minimo[i] - w[i]);
            folga += Math.max(0, w[i] - minimo[i]);
        }
        if (falta > 0 && folga > falta) {
            for (int i = 0; i < w.length; i++) {
                w[i] = w[i] < minimo[i] ? minimo[i] : w[i] - (w[i] - minimo[i]) / folga * falta;
            }
        }
        return w;
    }

    private void texto(String s, PDFont f, float tam, float x, float yBase, float[] cor) throws IOException {
        if (s == null || s.isEmpty()) {
            return;
        }
        float[] c = cor == null ? new float[] { 0.1f, 0.11f, 0.13f } : cor;
        cs.beginText();
        cs.setFont(f, tam);
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.newLineAtOffset(x, yBase);
        cs.showText(seguro(s, f));
        cs.endText();
    }

    private void linhaHorizontal(float yl, float[] cor, float espessura) throws IOException {
        cs.setStrokingColor(cor[0], cor[1], cor[2]);
        cs.setLineWidth(espessura);
        cs.moveTo(MARGEM, yl);
        cs.lineTo(MARGEM + LARGURA_UTIL, yl);
        cs.stroke();
    }

    private float largura(String s, PDFont f, float tam) throws IOException {
        return f.getStringWidth(seguro(s, f)) / 1000 * tam;
    }

    /** Quebra o texto em linhas que cabem na largura (por palavra; palavras longas são cortadas). */
    private List<String> quebrar(String s, PDFont f, float tam, float max) throws IOException {
        List<String> r = new ArrayList<>();
        String texto = s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (texto.isEmpty()) {
            r.add("");
            return r;
        }
        StringBuilder atual = new StringBuilder();
        for (String palavra : texto.split(" +")) {
            String tentativa = atual.isEmpty() ? palavra : atual + " " + palavra;
            if (largura(tentativa, f, tam) <= max) {
                atual = new StringBuilder(tentativa);
                continue;
            }
            if (!atual.isEmpty()) {
                r.add(atual.toString());
            }
            atual = new StringBuilder();
            String resto = palavra;
            while (largura(resto, f, tam) > max && resto.length() > 1) {
                int n = resto.length() - 1;
                while (n > 1 && largura(resto.substring(0, n), f, tam) > max) {
                    n--;
                }
                r.add(resto.substring(0, n));
                resto = resto.substring(n);
            }
            atual.append(resto);
        }
        if (!atual.isEmpty()) {
            r.add(atual.toString());
        }
        return r;
    }

    private String cortar(String s, PDFont f, float tam, float max) throws IOException {
        if (s == null) {
            return "";
        }
        String t = s;
        while (t.length() > 1 && largura(t, f, tam) > max) {
            t = t.substring(0, t.length() - 2) + "…";
        }
        return t;
    }

    /** Troca caracteres que a codificação WinAnsi das fontes padrão não tem (setas, sinais matemáticos...). */
    private String seguro(String s, PDFont f) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            String t = trocas.computeIfAbsent(ch, c -> {
                if (Character.isISOControl(c)) {
                    return " ";
                }
                try {
                    f.encode(String.valueOf(c));
                    return String.valueOf(c);
                } catch (IOException | IllegalArgumentException e) {
                    return switch (c) {
                        case '→' -> "->";
                        case '←' -> "<-";
                        case '≥' -> ">=";
                        case '≤' -> "<=";
                        case '−' -> "-";
                        case ' ', ' ' -> " ";
                        default -> "?";
                    };
                }
            });
            b.append(t);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ valores

    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    private static String tipo(String t) {
        return t == null ? "texto" : t.toLowerCase(Locale.ROOT);
    }

    static String formatar(Object v, String tipoColuna) {
        if (v == null) {
            return "–";
        }
        if (v instanceof Boolean b) {
            return b ? "Sim" : "Não";
        }
        String t = tipo(tipoColuna);
        if ("texto".equals(t)) {
            return String.valueOf(v);
        }
        BigDecimal n = numero(v);
        if (n == null) {
            String s = String.valueOf(v);
            return s.isBlank() ? "–" : s;
        }
        NumberFormat nf = NumberFormat.getNumberInstance(BR);
        switch (t) {
            case "moeda" -> {
                nf.setMinimumFractionDigits(2);
                nf.setMaximumFractionDigits(2);
                return nf.format(n);
            }
            case "percentual" -> {
                nf.setMaximumFractionDigits(6);
                return nf.format(n) + "%";
            }
            default -> {
                nf.setMaximumFractionDigits(6);
                return nf.format(n);
            }
        }
    }

    private static BigDecimal numero(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
