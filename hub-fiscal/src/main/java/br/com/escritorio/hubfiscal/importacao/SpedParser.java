package br.com.escritorio.hubfiscal.importacao;

import java.math.BigDecimal;
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

    /**
     * Resultado da leitura de um arquivo SPED.
     *
     * @param uf                UF do contribuinte (registro 0000)
     * @param municipio         código IBGE do município do contribuinte (registro 0000)
     * @param incidenciaPisCofins EFD Contribuições, registro 0110 (COD_INC_TRIB): 1 = não cumulativo, 2 = cumulativo,
     *                          3 = ambos; null quando o arquivo não informa
     */
    public record ResultadoSped(String tipo, String nome, String cnpj, String dataInicial, String dataFinal,
            List<ItemFiscal> cadastro, List<ItemFiscal> movimentos, String uf, Integer municipio,
            String incidenciaPisCofins) {
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

    /**
     * UTF-8 quando válido; senão ISO-8859-1, a codificação padrão do SPED. Sem o marcador de ordem (BOM) do início.
     * <p>
     * Desempenho: a validação percorre os bytes sem alocar nada e o texto é decodificado uma vez só. O decodificador
     * do JDK usado antes criava um buffer de caracteres do tamanho do arquivo e depois o copiava (SPED de 250 MB:
     * ~1 GB só nessa etapa).
     */
    static String decodificar(byte[] conteudo) {
        int ini = temBom(conteudo) ? 3 : 0;
        return new String(conteudo, ini, conteudo.length - ini,
                utf8Valido(conteudo, ini) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
    }

    private static boolean temBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    /** Sequência UTF-8 bem formada (mesmas regras do decodificador estrito do JDK: sem sobrelongos nem surrogates). */
    static boolean utf8Valido(byte[] b, int inicio) {
        int n = b.length;
        int i = inicio;
        while (i < n) {
            int x = b[i] & 0xFF;
            if (x < 0x80) {
                i++;
                continue;
            }
            int k;
            int min2 = 0x80;
            int max2 = 0xBF;
            if (x >= 0xC2 && x <= 0xDF) {
                k = 1;
            } else if (x >= 0xE0 && x <= 0xEF) {
                k = 2;
                if (x == 0xE0) {
                    min2 = 0xA0; // sobrelongo
                } else if (x == 0xED) {
                    max2 = 0x9F; // surrogates
                }
            } else if (x >= 0xF0 && x <= 0xF4) {
                k = 3;
                if (x == 0xF0) {
                    min2 = 0x90;
                } else if (x == 0xF4) {
                    max2 = 0x8F;
                }
            } else {
                return false;
            }
            if (i + k >= n) {
                return false;
            }
            int b2 = b[i + 1] & 0xFF;
            if (b2 < min2 || b2 > max2) {
                return false;
            }
            for (int j = 2; j <= k; j++) {
                if ((b[i + j] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += k + 1;
        }
        return true;
    }

    /** Registros lidos; os demais (C190, E110, ...) são pulados sem criar texto nem dividir campos. */
    private static final String[] REGISTROS_USADOS = { "|0000|", "|0110|", "|0200|", "|C100|", "|C170|", "|A100|", "|A170|",
            "|C400|", "|C405|", "|C425|" };

    private static boolean usado(String texto, int inicio) {
        for (String r : REGISTROS_USADOS) {
            if (texto.startsWith(r, inicio)) {
                return true;
            }
        }
        return false;
    }

    /** Lê o arquivo: contribuinte, período, cadastro de itens (0200) e movimentos completados pelo cadastro. */
    public static ResultadoSped ler(byte[] conteudo) {
        String tipo = "EFD";
        String nome = null;
        String cnpj = null;
        String dtIni = null;
        String dtFin = null;
        String uf = null;
        Integer municipio = null;
        String incidencia = null;

        Map<String, ItemFiscal> cadastro = new HashMap<>();
        List<ItemFiscal> ordemCadastro = new ArrayList<>();
        List<ItemFiscal> movimentos = new ArrayList<>();
        Integer modeloDocumento = null; // modelo do documento corrente (C100); A100 e C400 não têm modelo numérico
        String operacaoDocumento = null; // IND_OPER do documento corrente: 0 = entrada, 1 = saída
        String chaveDocumento = null; // chave da NF-e do C100 corrente (evita contar a nota duas vezes com o XML)

        // percorre as linhas sem montar um array com todas elas (SPED grande: milhões de linhas)
        String texto = decodificar(conteudo);
        int tamanho = texto.length();
        int proxima = 0;
        while (proxima < tamanho) {
            int inicio = proxima;
            int fim = texto.indexOf('\n', inicio);
            if (fim < 0) {
                fim = tamanho;
            }
            proxima = fim + 1;
            if (fim > inicio && texto.charAt(fim - 1) == '\r') {
                fim--;
            }
            if (fim - inicio < 6 || !usado(texto, inicio)) {
                continue;
            }
            String[] f = texto.substring(inicio, fim).split("\\|", -1);
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
                        uf = campo(f, 9);
                        municipio = Valores.inteiro(campo(f, 11));
                    } else if (f.length > 10) {
                        tipo = "EFD Contribuições";
                        dtIni = dataSped(f[6]);
                        dtFin = dataSped(f[7]);
                        nome = f[8];
                        cnpj = f[9];
                        uf = campo(f, 10);
                        municipio = Valores.inteiro(campo(f, 11));
                    }
                }
                case "0110" -> incidencia = campo(f, 2);
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
                case "C100" -> {
                    // |C100|IND_OPER|IND_EMIT|COD_PART|COD_MOD|COD_SIT|SER|NUM_DOC|CHV_NFE|...
                    modeloDocumento = Valores.inteiro(campo(f, 5));
                    operacaoDocumento = campo(f, 2);
                    chaveDocumento = campo(f, 9);
                }
                case "A100" -> {
                    // |A100|IND_OPER|...: 0 = serviço contratado (entrada), 1 = serviço prestado (saída)
                    modeloDocumento = null;
                    operacaoDocumento = campo(f, 2);
                    chaveDocumento = null;
                }
                case "C400", "C405" -> {
                    modeloDocumento = null;
                    operacaoDocumento = "1"; // cupom fiscal (ECF): venda
                    chaveDocumento = null;
                }
                case "C170" -> {
                    ItemFiscal it = novoItem("C170", cnpj, nome, modeloDocumento);
                    it.numeroItem = Valores.inteiro(campo(f, 2));
                    it.codigo = campo(f, 3);
                    it.quantidade = decimalSped(campo(f, 5));
                    it.unidade = campo(f, 6);
                    it.valor = decimalSped(campo(f, 7));
                    it.desconto = decimalSped(campo(f, 8));
                    it.cfop = campo(f, 11);
                    it.saida = "1".equals(operacaoDocumento);
                    it.chave = chaveDocumento;
                    movimentos.add(it);
                }
                case "A170" -> {
                    ItemFiscal it = novoItem("A170", cnpj, nome, modeloDocumento);
                    it.numeroItem = Valores.inteiro(campo(f, 2));
                    it.codigo = campo(f, 3);
                    it.valor = decimalSped(campo(f, 5));
                    it.desconto = decimalSped(campo(f, 6));
                    it.saida = "1".equals(operacaoDocumento);
                    movimentos.add(it);
                }
                case "C425" -> {
                    ItemFiscal it = novoItem("C425", cnpj, nome, null);
                    it.codigo = campo(f, 2);
                    it.quantidade = decimalSped(campo(f, 3));
                    it.unidade = campo(f, 4);
                    it.valor = decimalSped(campo(f, 5));
                    it.saida = true;
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
        if (uf != null && !uf.matches("[A-Za-z]{2}")) {
            uf = null;
        }
        return new ResultadoSped(tipo, nome, cnpj, dtIni, dtFin, ordemCadastro, movimentos,
                uf == null ? null : uf.toUpperCase(), municipio, incidencia);
    }

    private static ItemFiscal novoItem(String registro, String cnpj, String nome, Integer modelo) {
        ItemFiscal it = ItemFiscal.semGrupos(); // o SPED não tem componentes de base nem grupo IBS/CBS
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
