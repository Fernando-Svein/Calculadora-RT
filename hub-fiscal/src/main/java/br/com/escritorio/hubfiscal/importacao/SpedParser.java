package br.com.escritorio.hubfiscal.importacao;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.escritorio.hubfiscal.infra.Valores;

/**
 * Leitor de arquivos SPED: EFD ICMS/IPI e EFD Contribuições.
 * <p>
 * Registros usados:
 * <ul>
 * <li>0000 – contribuinte e período</li>
 * <li>0200 – cadastro de itens (código, descrição, NCM, tipo do item)</li>
 * <li>C100/C170 – documentos e itens de mercadorias (modelo, CFOP, valores)</li>
 * <li>A100/A170 – serviços (EFD Contribuições)</li>
 * <li>C400/C425 – itens de cupom fiscal (ECF)</li>
 * </ul>
 * No EFD ICMS/IPI, as saídas com NF-e própria normalmente não têm C170; para
 * elas o cadastro 0200 é a fonte principal (os XMLs complementam com os CFOPs).
 */
public final class SpedParser {

    /** Resultado da leitura de um arquivo SPED. */
    public record ResultadoSped(String tipo, String nome, String cnpj, String dataInicial, String dataFinal,
            List<ItemFiscal> cadastro, List<ItemFiscal> movimentos) {
    }

    private SpedParser() {
    }

    /** O conteúdo começa com o registro 0000 (ignorando BOM)? */
    public static boolean pareceSped(byte[] conteudo) {
        int i = 0;
        if (conteudo.length >= 3 && (conteudo[0] & 0xFF) == 0xEF && (conteudo[1] & 0xFF) == 0xBB) {
            i = 3;
        }
        return conteudo.length > i + 6 && conteudo[i] == '|' && new String(conteudo, i, 6, StandardCharsets.ISO_8859_1).equals("|0000|");
    }

    /** UTF-8 quando válido; senão ISO-8859-1, a codificação padrão do SPED. */
    static String decodificar(byte[] conteudo) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(conteudo)).toString();
        } catch (CharacterCodingException e) {
            return new String(conteudo, StandardCharsets.ISO_8859_1);
        }
    }

    /** Lê o arquivo: contribuinte, período, cadastro de itens (0200) e movimentos completados pelo cadastro. */
    public static ResultadoSped ler(byte[] conteudo) {
        String tipo = "EFD";
        String nome = null;
        String cnpj = null;
        String dtIni = null;
        String dtFin = null;

        Map<String, ItemFiscal> cadastro = new HashMap<>();
        List<ItemFiscal> ordemCadastro = new ArrayList<>();
        List<ItemFiscal> movimentos = new ArrayList<>();
        Integer modeloDocumento = null; // modelo do documento corrente (C100); A100 e C400 não têm modelo numérico

        for (String linha : decodificar(conteudo).split("\r?\n")) {
            if (linha.length() < 6 || linha.charAt(0) != '|') {
                continue;
            }
            String[] f = linha.split("\\|", -1);
            if (f.length < 3) {
                continue;
            }
            switch (f[1]) {
                case "0000" -> {
                    if (f.length > 9 && f[4].matches("\\d{8}") && f[7].matches("\\d{14}")) {
                        tipo = "EFD ICMS/IPI";
                        dtIni = dataSped(f[4]);
                        dtFin = dataSped(f[5]);
                        nome = f[6];
                        cnpj = f[7];
                    } else if (f.length > 10) {
                        tipo = "EFD Contribuições";
                        dtIni = dataSped(f[6]);
                        dtFin = dataSped(f[7]);
                        nome = f[8];
                        cnpj = f[9];
                    }
                }
                case "0200" -> {
                    ItemFiscal it = novoItem("0200", cnpj, nome, null);
                    it.codigo = campo(f, 2);
                    it.descricao = campo(f, 3);
                    it.unidade = campo(f, 6);
                    it.tipoItemSped = campo(f, 7);
                    it.ncm = somenteDigitos(campo(f, 8));
                    if (it.codigo != null) {
                        cadastro.put(it.codigo, it);
                        ordemCadastro.add(it);
                    }
                }
                case "C100" -> modeloDocumento = Valores.inteiro(campo(f, 5));
                case "A100", "C400", "C405" -> modeloDocumento = null;
                case "C170" -> {
                    ItemFiscal it = novoItem("C170", cnpj, nome, modeloDocumento);
                    it.numeroItem = Valores.inteiro(campo(f, 2));
                    it.codigo = campo(f, 3);
                    it.quantidade = decimalSped(campo(f, 5));
                    it.unidade = campo(f, 6);
                    it.valor = decimalSped(campo(f, 7));
                    it.cfop = campo(f, 11);
                    movimentos.add(it);
                }
                case "A170" -> {
                    ItemFiscal it = novoItem("A170", cnpj, nome, modeloDocumento);
                    it.numeroItem = Valores.inteiro(campo(f, 2));
                    it.codigo = campo(f, 3);
                    it.valor = decimalSped(campo(f, 5));
                    movimentos.add(it);
                }
                case "C425" -> {
                    ItemFiscal it = novoItem("C425", cnpj, nome, null);
                    it.codigo = campo(f, 2);
                    it.quantidade = decimalSped(campo(f, 3));
                    it.unidade = campo(f, 4);
                    it.valor = decimalSped(campo(f, 5));
                    movimentos.add(it);
                }
                default -> {
                    // demais registros não são usados
                }
            }
        }
        // completa os movimentos com os dados do cadastro e marca a origem (o tipo só é conhecido após o 0000)
        for (ItemFiscal m : movimentos) {
            ItemFiscal c = cadastro.get(m.codigo);
            if (c != null) {
                m.descricao = c.descricao;
                m.ncm = c.ncm;
                m.tipoItemSped = c.tipoItemSped;
            }
            m.origem = tipo;
        }
        for (ItemFiscal c : ordemCadastro) {
            c.origem = tipo;
        }
        return new ResultadoSped(tipo, nome, cnpj, dtIni, dtFin, ordemCadastro, movimentos);
    }

    private static ItemFiscal novoItem(String registro, String cnpj, String nome, Integer modelo) {
        ItemFiscal it = new ItemFiscal();
        it.registro = registro;
        it.donoCnpj = cnpj;
        it.donoNome = nome;
        it.modelo = modelo;
        return it;
    }

    private static String campo(String[] f, int i) {
        if (i >= f.length) {
            return null;
        }
        String v = f[i].trim();
        return v.isEmpty() ? null : v;
    }

    /** Só os dígitos do texto (NCM/NBS com pontos), ou null se não houver nenhum. */
    static String somenteDigitos(String s) {
        if (s == null) {
            return null;
        }
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    /** Valor no formato do SPED (vírgula decimal, ponto de milhar opcional): "1.234,56". */
    private static BigDecimal decimalSped(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim().replace(".", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ddMMaaaa → aaaa-MM-dd */
    private static String dataSped(String s) {
        if (s == null || !s.matches("\\d{8}")) {
            return null;
        }
        return s.substring(4) + "-" + s.substring(2, 4) + "-" + s.substring(0, 2);
    }
}
