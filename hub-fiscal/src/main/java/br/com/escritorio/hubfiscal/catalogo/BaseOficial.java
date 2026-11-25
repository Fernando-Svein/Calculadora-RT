package br.com.escritorio.hubfiscal.catalogo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;

/**
 * Acesso somente leitura ao banco SQLite oficial da calculadora (o mesmo
 * arquivo usado pelas APIs da Receita).
 * <p>
 * É usado para o que as APIs não oferecem pronto, principalmente a pergunta
 * inversa "quais cClassTrib se aplicam a este NCM/NBS?". A regra de vínculo
 * reproduz exatamente a da calculadora oficial
 * ({@code NcmAplicavelService}/{@code NcmAplicavelRepository}): o código do
 * anexo funciona como prefixo do NCM, e as exceções vigentes excluem o NCM
 * quando não houver outro vínculo vigente sem exceções.
 */
@Component
public class BaseOficial {

    /** Tipo de nomenclatura: NCM (bens) ou NBS (serviços). */
    public enum Tipo {
        NCM("NCM_APLICAVEL", "NCMA", "EXCECAO_NCM_APLICAVEL", "ENCM", "NCM", "NCM"),
        NBS("NBS_APLICAVEL", "NBSA", "EXCECAO_NBS_APLICAVEL", "ENBS", "NBS", "NBS");

        final String tabela;
        final String p;
        final String tabelaExcecao;
        final String pe;
        final String tabelaNomenclatura;
        final String pn;

        Tipo(String tabela, String p, String tabelaExcecao, String pe, String tabelaNomenclatura, String pn) {
            this.tabela = tabela;
            this.p = p;
            this.tabelaExcecao = tabelaExcecao;
            this.pe = pe;
            this.tabelaNomenclatura = tabelaNomenclatura;
            this.pn = pn;
        }
    }

    /** Vínculo de uma classificação com o código pesquisado, via anexo. */
    public record Vinculo(String cClassTrib, String codigoAnexo, String anexo, String itemAnexo, String textoItemAnexo) {
    }

    /** Nomenclatura (NCM/NBS) com descrição hierárquica. */
    public record Nomenclatura(String codigo, String descricao, String descricaoCompleta, String lc116,
            String inicioVigencia, String fimVigencia, boolean vigente) {
    }

    /** Linha do anexo de uma classificação (prefixo NCM/NBS coberto). */
    public record LinhaAnexo(String codigo, String descricao, String anexo, String itemAnexo, String textoItemAnexo,
            List<String> excecoes) {
    }

    /**
     * Restringe às classificações de CBS/IBS. O Imposto Seletivo tem situações
     * tributárias próprias (tributo 1) e reutiliza códigos de cClassTrib (ex.:
     * "000001"), por isso é preciso filtrar pelo tributo da situação.
     */
    private static final String SOMENTE_CBS_IBS = " AND EXISTS (SELECT 1 FROM TRIBUTO_SITUACAO_TRIBUTARIA ts "
            + "WHERE ts.TRST_SITR_ID = c.CLTR_SITR_ID AND ts.TRST_TBTO_ID IN (2, 3, 4))";

    private final Path arquivo;

    public BaseOficial(HubProperties props) {
        this.arquivo = Path.of(props.bancoOficial()).toAbsolutePath().normalize();
    }

    public Path arquivo() {
        return arquivo;
    }

    public boolean disponivel() {
        return Files.isRegularFile(arquivo);
    }

    private Connection conectar() throws SQLException {
        if (!disponivel()) {
            throw new ErroNegocio("Banco oficial não encontrado em " + arquivo
                    + ". Gere o banco com o Flyway no repositório calculadora-rtc-regime-regular.");
        }
        Properties p = new Properties();
        p.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        return DriverManager.getConnection("jdbc:sqlite:" + arquivo.toString().replace('\\', '/'), p);
    }

    private static String d(LocalDate data) {
        return data.toString();
    }

    /**
     * Classificações (códigos cClassTrib) que possuem anexo vigente para o tipo
     * informado, ou seja, que só aceitam os NCM/NBS listados.
     */
    public Set<String> classificacoesComAnexo(Tipo t, LocalDate data) {
        String sql = "SELECT DISTINCT c.CLTR_CD FROM " + t.tabela + " n "
                + "JOIN CLASSIFICACAO_TRIBUTARIA c ON c.CLTR_ID = n." + t.p + "_CLTR_ID "
                + "WHERE ? BETWEEN n." + t.p + "_INICIO_VIGENCIA AND COALESCE(n." + t.p + "_FIM_VIGENCIA, ?) "
                + "AND ? BETWEEN c.CLTR_INICIO_VIGENCIA AND COALESCE(c.CLTR_FIM_VIGENCIA, ?)" + SOMENTE_CBS_IBS + "";
        Set<String> r = new TreeSet<>();
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            String s = d(data);
            ps.setString(1, s);
            ps.setString(2, s);
            ps.setString(3, s);
            ps.setString(4, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    r.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o banco oficial", e);
        }
        return r;
    }

    /**
     * Classificações cujo anexo cobre o código informado (NCM com 8 dígitos ou
     * NBS com 9), aplicando a mesma tabela-verdade da calculadora oficial:
     * rejeita quando não há vínculo (A) ou quando há exceção vigente (E) sem
     * outro vínculo vigente livre de exceção (S).
     */
    public List<Vinculo> vinculos(Tipo t, String codigo, LocalDate data) {
        String p = t.p;
        String sql = "SELECT n." + p + "_ID, c.CLTR_CD, n." + p + "_" + t.pn + "_CD, a.ANXO_NUMERO, a.ANXO_NUMERO_ITEM, a.ANXO_TEXTO_ITEM "
                + "FROM " + t.tabela + " n "
                + "JOIN CLASSIFICACAO_TRIBUTARIA c ON c.CLTR_ID = n." + p + "_CLTR_ID "
                + "LEFT JOIN ANEXO a ON a.ANXO_ID = n." + p + "_ANXO_ID "
                + "WHERE n." + p + "_" + t.pn + "_CD = substr(?, 1, length(n." + p + "_" + t.pn + "_CD)) "
                + "AND ? BETWEEN n." + p + "_INICIO_VIGENCIA AND COALESCE(n." + p + "_FIM_VIGENCIA, ?) "
                + "AND ? BETWEEN c.CLTR_INICIO_VIGENCIA AND COALESCE(c.CLTR_FIM_VIGENCIA, ?)" + SOMENTE_CBS_IBS + "";
        // Exceções de cada vínculo: código da exceção e se está vigente
        String sqlExc = "SELECT e." + t.pe + "_" + t.pn + "_CD, "
                + "CASE WHEN ? BETWEEN e." + t.pe + "_INICIO_VIGENCIA AND COALESCE(e." + t.pe + "_FIM_VIGENCIA, ?) THEN 1 ELSE 0 END "
                + "FROM " + t.tabelaExcecao + " e WHERE e." + t.pe + "_" + t.pn + "A_ID = ?";
        String s = d(data);
        Map<String, List<Object[]>> porClassificacao = new LinkedHashMap<>();
        try (Connection c = conectar();
                PreparedStatement ps = c.prepareStatement(sql);
                PreparedStatement pe = c.prepareStatement(sqlExc)) {
            ps.setString(1, codigo);
            ps.setString(2, s);
            ps.setString(3, s);
            ps.setString(4, s);
            ps.setString(5, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String cct = rs.getString(2);
                    String cod = rs.getString(3);
                    // Para NBS, o vínculo com a tabela ANEXO da base oficial é inconsistente
                    // (ex.: educação aponta para "Anexo IX – Vinhaça"): não exibimos o anexo.
                    boolean anexoConfiavel = t == Tipo.NCM;
                    Vinculo v = new Vinculo(cct, cod, anexoConfiavel ? rs.getString(4) : null,
                            anexoConfiavel ? rs.getString(5) : null, anexoConfiavel ? rs.getString(6) : null);
                    // exceções do vínculo
                    boolean temExcecaoQualquer = false;
                    boolean temExcecaoNaoVigente = false;
                    boolean excecaoVigenteCobre = false;
                    pe.setString(1, s);
                    pe.setString(2, s);
                    pe.setLong(3, id);
                    try (ResultSet re = pe.executeQuery()) {
                        while (re.next()) {
                            temExcecaoQualquer = true;
                            String codExc = re.getString(1);
                            boolean vigente = re.getInt(2) == 1;
                            if (!vigente) {
                                temExcecaoNaoVigente = true;
                            } else if (codExc != null && codigo.startsWith(codExc)) {
                                excecaoVigenteCobre = true;
                            }
                        }
                    }
                    // S (por linha): sem exceções, ou com alguma exceção não vigente (semântica do LEFT JOIN oficial)
                    boolean semExcecao = !temExcecaoQualquer || temExcecaoNaoVigente;
                    porClassificacao.computeIfAbsent(cct, k -> new ArrayList<>())
                            .add(new Object[] { v, excecaoVigenteCobre, semExcecao });
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o banco oficial", e);
        }
        List<Vinculo> validos = new ArrayList<>();
        for (List<Object[]> linhas : porClassificacao.values()) {
            boolean a = !linhas.isEmpty();
            boolean e = linhas.stream().anyMatch(l -> (Boolean) l[1]);
            boolean sx = linhas.stream().anyMatch(l -> (Boolean) l[2]);
            if (a && !(e && !sx)) {
                // usa o vínculo mais específico (código mais longo) para exibir o anexo
                Vinculo melhor = linhas.stream().map(l -> (Vinculo) l[0])
                        .max((x, y) -> Integer.compare(x.codigoAnexo().length(), y.codigoAnexo().length()))
                        .orElseThrow();
                validos.add(melhor);
            }
        }
        return validos;
    }

    /** Dados do NCM/NBS, com a descrição montada a partir da hierarquia. */
    public Optional<Nomenclatura> nomenclatura(Tipo t, String codigo, LocalDate data) {
        String tab = t.tabelaNomenclatura;
        String pn = t.pn;
        String colLc = t == Tipo.NBS ? ", NBS_LC_116" : ", NULL";
        String sql = "SELECT " + pn + "_CD, " + pn + "_DESCRICAO, " + pn + "_INICIO_VIGENCIA, " + pn + "_FIM_VIGENCIA" + colLc
                + " FROM " + tab + " WHERE ? LIKE " + pn + "_CD || '%' ORDER BY length(" + pn + "_CD)";
        List<String[]> hierarquia = new ArrayList<>();
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hierarquia.add(new String[] { rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5) });
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o banco oficial", e);
        }
        String[] proprio = hierarquia.stream().filter(h -> h[0].equals(codigo)).findFirst().orElse(null);
        if (proprio == null) {
            return Optional.empty();
        }
        StringBuilder completa = new StringBuilder();
        for (String[] h : hierarquia) {
            String desc = limparDescricao(h[1]);
            if (desc.isBlank()) {
                continue;
            }
            if (completa.length() > 0) {
                completa.append(" › ");
            }
            completa.append(desc);
        }
        String s = d(data);
        boolean vigente = s.compareTo(proprio[2]) >= 0 && (proprio[3] == null || s.compareTo(proprio[3]) <= 0);
        return Optional.of(new Nomenclatura(codigo, limparDescricao(proprio[1]), completa.toString(), proprio[4],
                proprio[2], proprio[3], vigente));
    }

    private static String limparDescricao(String d) {
        // a tabela oficial traz marcações HTML em algumas descrições (ex.: "<i>paddy</i>")
        return d == null ? "" : d.replaceAll("<[^>]+>", "").replaceFirst("^[-\\s]+", "").trim();
    }

    /** Busca por código (prefixo) ou por palavra na descrição. */
    public List<Nomenclatura> buscar(Tipo t, String termo, LocalDate data, int limite) {
        String pn = t.pn;
        String colLc = t == Tipo.NBS ? ", NBS_LC_116" : ", NULL";
        String limpo = termo.replaceAll("[.\\s-]", "");
        boolean numerico = limpo.matches("\\d+");
        String sql = "SELECT " + pn + "_CD, " + pn + "_DESCRICAO, " + pn + "_INICIO_VIGENCIA, " + pn + "_FIM_VIGENCIA" + colLc
                + " FROM " + t.tabelaNomenclatura + " WHERE "
                + (numerico ? pn + "_CD LIKE ? " : pn + "_DESCRICAO LIKE ? ESCAPE '\\' ")
                + "AND ? BETWEEN " + pn + "_INICIO_VIGENCIA AND COALESCE(" + pn + "_FIM_VIGENCIA, ?) "
                + "ORDER BY " + pn + "_CD LIMIT ?";
        List<Nomenclatura> r = new ArrayList<>();
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            // % e _ digitados pelo usuário são texto literal, não curingas (ex.: buscar "100%")
            String literal = termo.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            ps.setString(1, numerico ? limpo + "%" : "%" + literal + "%");
            ps.setString(2, d(data));
            ps.setString(3, d(data));
            ps.setInt(4, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String desc = limparDescricao(rs.getString(2));
                    r.add(new Nomenclatura(rs.getString(1), desc, desc, rs.getString(5), rs.getString(3),
                            rs.getString(4), true));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o banco oficial", e);
        }
        return r;
    }

    /** NCM/NBS cobertos pelo anexo de uma classificação (prefixos), com exceções. */
    public List<LinhaAnexo> anexoDaClassificacao(Tipo t, String cClassTrib, LocalDate data) {
        String p = t.p;
        String pn = t.pn;
        String sql = "SELECT n." + p + "_ID, n." + p + "_" + pn + "_CD, nm." + pn + "_DESCRICAO, a.ANXO_NUMERO, a.ANXO_NUMERO_ITEM, a.ANXO_TEXTO_ITEM "
                + "FROM " + t.tabela + " n "
                + "JOIN CLASSIFICACAO_TRIBUTARIA c ON c.CLTR_ID = n." + p + "_CLTR_ID "
                + "LEFT JOIN ANEXO a ON a.ANXO_ID = n." + p + "_ANXO_ID "
                + "LEFT JOIN " + t.tabelaNomenclatura + " nm ON nm." + pn + "_CD = n." + p + "_" + pn + "_CD "
                + "WHERE c.CLTR_CD = ? "
                + "AND ? BETWEEN n." + p + "_INICIO_VIGENCIA AND COALESCE(n." + p + "_FIM_VIGENCIA, ?) "
                + "AND ? BETWEEN c.CLTR_INICIO_VIGENCIA AND COALESCE(c.CLTR_FIM_VIGENCIA, ?)" + SOMENTE_CBS_IBS + " "
                + "ORDER BY n." + p + "_" + pn + "_CD";
        String sqlExc = "SELECT e." + t.pe + "_" + pn + "_CD FROM " + t.tabelaExcecao + " e WHERE e." + t.pe + "_" + pn + "A_ID = ? "
                + "AND ? BETWEEN e." + t.pe + "_INICIO_VIGENCIA AND COALESCE(e." + t.pe + "_FIM_VIGENCIA, ?)";
        String s = d(data);
        List<LinhaAnexo> r = new ArrayList<>();
        try (Connection c = conectar();
                PreparedStatement ps = c.prepareStatement(sql);
                PreparedStatement pe = c.prepareStatement(sqlExc)) {
            ps.setString(1, cClassTrib);
            ps.setString(2, s);
            ps.setString(3, s);
            ps.setString(4, s);
            ps.setString(5, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> exc = new ArrayList<>();
                    pe.setLong(1, rs.getLong(1));
                    pe.setString(2, s);
                    pe.setString(3, s);
                    try (ResultSet re = pe.executeQuery()) {
                        while (re.next()) {
                            exc.add(re.getString(1));
                        }
                    }
                    boolean anexoConfiavel = t == Tipo.NCM; // ver comentário em vinculos()
                    r.add(new LinhaAnexo(rs.getString(2), limparDescricao(rs.getString(3)),
                            anexoConfiavel ? rs.getString(4) : null, anexoConfiavel ? rs.getString(5) : null,
                            anexoConfiavel ? rs.getString(6) : null, exc));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o banco oficial", e);
        }
        return r;
    }

    /** Versão do banco oficial (ex.: V0073) e data. */
    public Map<String, String> versao() {
        Map<String, String> r = new LinkedHashMap<>();
        if (!disponivel()) {
            r.put("erro", "Banco não encontrado em " + arquivo);
            return r;
        }
        String sql = "SELECT VRBD_VERSAO_BASE_DADO, VRBD_DATA, VRBD_DESCRICAO FROM VERSAO_BASE_DADO ORDER BY VRBD_ID DESC LIMIT 1";
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                r.put("versao", rs.getString(1));
                r.put("data", rs.getString(2));
                r.put("descricao", rs.getString(3));
            }
        } catch (SQLException e) {
            r.put("erro", e.getMessage());
        }
        r.put("arquivo", arquivo.toString());
        return r;
    }
}
