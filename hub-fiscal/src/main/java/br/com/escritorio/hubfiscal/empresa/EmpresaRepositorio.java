package br.com.escritorio.hubfiscal.empresa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import br.com.escritorio.hubfiscal.config.HubProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Empresas num arquivo SQLite próprio (separado do banco oficial, que é somente leitura, e do banco de usuários).
 * O CNPJ é único: a importação usa INSERT OR IGNORE, então o mesmo CNPJ nunca gera dois cadastros,
 * mesmo com dois lotes processados ao mesmo tempo.
 */
@Repository
public class EmpresaRepositorio {

    private static final Logger log = LoggerFactory.getLogger(EmpresaRepositorio.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, String>>> TIPO_ALIQUOTAS = new TypeReference<>() {
    };

    private final String url;

    public EmpresaRepositorio(HubProperties props) {
        Path arquivo = Path.of(props.bancoEmpresas()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(arquivo.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível criar a pasta do banco de empresas: " + arquivo.getParent(), e);
        }
        this.url = "jdbc:sqlite:" + arquivo;
        executar("""
                CREATE TABLE IF NOT EXISTS empresa (
                  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                  cnpj                TEXT NOT NULL UNIQUE,
                  razao_social        TEXT NOT NULL,
                  nome_fantasia       TEXT,
                  uf                  TEXT,
                  municipio           INTEGER,
                  regime              TEXT,
                  regime_apuracao     TEXT,
                  ibscbs_fora_simples INTEGER NOT NULL DEFAULT 0,
                  fator_r             INTEGER NOT NULL DEFAULT 0,
                  sublimite_excedido  INTEGER NOT NULL DEFAULT 0,
                  atividade_simples   TEXT,
                  aliquotas_simples   TEXT,
                  documento_padrao    TEXT,
                  cclasstrib_padrao   TEXT,
                  observacoes         TEXT,
                  origem              TEXT NOT NULL,
                  revisado            INTEGER NOT NULL DEFAULT 0,
                  criado_em           TEXT NOT NULL,
                  atualizado_em       TEXT
                )""");
        log.info("Banco de empresas: {}", arquivo);
    }

    private Connection conectar() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private int executar(String sql, Object... params) {
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de empresas: " + e.getMessage(), e);
        }
    }

    private List<Empresa> consultar(String sql, Object... params) {
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<Empresa> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int mun = rs.getInt("municipio");
                    lista.add(new Empresa(rs.getLong("id"), rs.getString("cnpj"), rs.getString("razao_social"),
                            rs.getString("nome_fantasia"), rs.getString("uf"), rs.wasNull() ? null : mun,
                            rs.getString("regime"), rs.getString("regime_apuracao"), rs.getInt("ibscbs_fora_simples") == 1,
                            rs.getInt("fator_r") == 1, rs.getInt("sublimite_excedido") == 1, rs.getString("atividade_simples"),
                            lerAliquotas(rs.getString("aliquotas_simples")), rs.getString("documento_padrao"),
                            rs.getString("cclasstrib_padrao"), rs.getString("observacoes"), rs.getString("origem"),
                            rs.getInt("revisado") == 1, rs.getString("criado_em"), rs.getString("atualizado_em")));
                }
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de empresas: " + e.getMessage(), e);
        }
    }

    private static Map<String, Map<String, String>> lerAliquotas(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, TIPO_ALIQUOTAS);
        } catch (Exception e) {
            log.warn("Alíquotas do Simples ilegíveis no banco de empresas: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static String gravarAliquotas(Map<String, Map<String, String>> a) {
        return a == null || a.isEmpty() ? null : JSON.writeValueAsString(a);
    }

    private static String agora() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public List<Empresa> listar() {
        return consultar("SELECT * FROM empresa ORDER BY razao_social COLLATE NOCASE");
    }

    public Optional<Empresa> porId(long id) {
        return consultar("SELECT * FROM empresa WHERE id = ?", id).stream().findFirst();
    }

    public Optional<Empresa> porCnpj(String cnpj) {
        return consultar("SELECT * FROM empresa WHERE cnpj = ?", cnpj).stream().findFirst();
    }

    /**
     * Cadastro automático (importação): só insere se o CNPJ ainda não existe. Devolve true se cadastrou.
     * Empresas já cadastradas não são alteradas (as particularidades conferidas pelo escritório prevalecem).
     */
    public boolean inserirSeNovo(String cnpj, String razaoSocial, String nomeFantasia, String uf, Integer municipio,
            String regime, String regimeApuracao, String origem) {
        return executar("""
                INSERT OR IGNORE INTO empresa (cnpj, razao_social, nome_fantasia, uf, municipio, regime, regime_apuracao,
                  origem, revisado, criado_em) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)""",
                cnpj, razaoSocial, nomeFantasia, uf, municipio, regime, regimeApuracao, origem, agora()) == 1;
    }

    /** Cadastro manual. Devolve o id criado. */
    public long inserir(Empresa e) {
        executar("""
                INSERT INTO empresa (cnpj, razao_social, nome_fantasia, uf, municipio, regime, regime_apuracao,
                  ibscbs_fora_simples, fator_r, sublimite_excedido, atividade_simples, aliquotas_simples, documento_padrao,
                  cclasstrib_padrao, observacoes, origem, revisado, criado_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', 1, ?, ?)""",
                e.cnpj(), e.razaoSocial(), e.nomeFantasia(), e.uf(), e.municipio(), e.regime(), e.regimeApuracao(),
                e.ibscbsForaSimples() ? 1 : 0, e.fatorR() ? 1 : 0, e.sublimiteExcedido() ? 1 : 0, e.atividadeSimples(),
                gravarAliquotas(e.aliquotasSimples()), e.documentoPadrao(), e.cClassTribPadrao(), e.observacoes(), agora(), agora());
        return porCnpj(e.cnpj()).map(Empresa::id).orElseThrow();
    }

    /** Alteração das particularidades (o CNPJ e a origem não mudam). Marca o cadastro como revisado. */
    public void atualizar(long id, Empresa e) {
        executar("""
                UPDATE empresa SET razao_social = ?, nome_fantasia = ?, uf = ?, municipio = ?, regime = ?, regime_apuracao = ?,
                  ibscbs_fora_simples = ?, fator_r = ?, sublimite_excedido = ?, atividade_simples = ?, aliquotas_simples = ?,
                  documento_padrao = ?, cclasstrib_padrao = ?, observacoes = ?, revisado = 1, atualizado_em = ?
                WHERE id = ?""",
                e.razaoSocial(), e.nomeFantasia(), e.uf(), e.municipio(), e.regime(), e.regimeApuracao(),
                e.ibscbsForaSimples() ? 1 : 0, e.fatorR() ? 1 : 0, e.sublimiteExcedido() ? 1 : 0, e.atividadeSimples(),
                gravarAliquotas(e.aliquotasSimples()), e.documentoPadrao(), e.cClassTribPadrao(), e.observacoes(), agora(), id);
    }

    public void excluir(long id) {
        executar("DELETE FROM empresa WHERE id = ?", id);
    }
}
