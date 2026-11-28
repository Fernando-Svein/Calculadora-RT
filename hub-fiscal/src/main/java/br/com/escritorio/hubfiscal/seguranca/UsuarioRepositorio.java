package br.com.escritorio.hubfiscal.seguranca;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.seguranca.Usuario.Perfil;

/** Usuários do hub num arquivo SQLite próprio (separado do banco oficial, que é somente leitura). */
@Repository
public class UsuarioRepositorio {

    private static final Logger log = LoggerFactory.getLogger(UsuarioRepositorio.class);

    private final String url;

    public UsuarioRepositorio(HubProperties props) {
        Path arquivo = Path.of(props.bancoUsuarios()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(arquivo.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível criar a pasta do banco de usuários: " + arquivo.getParent(), e);
        }
        this.url = "jdbc:sqlite:" + arquivo;
        executar("""
                CREATE TABLE IF NOT EXISTS usuario (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  login         TEXT NOT NULL UNIQUE COLLATE NOCASE,
                  nome          TEXT NOT NULL,
                  senha_hash    TEXT NOT NULL,
                  perfil        TEXT NOT NULL,
                  ativo         INTEGER NOT NULL DEFAULT 1,
                  trocar_senha  INTEGER NOT NULL DEFAULT 0,
                  criado_em     TEXT NOT NULL,
                  ultimo_acesso TEXT
                )""");
        // versão 2: ferramentas liberadas por usuário (NULL = todas, mantém quem já existia com acesso total)
        if (!colunaExiste("usuario", "permissoes")) {
            executar("ALTER TABLE usuario ADD COLUMN permissoes TEXT");
        }
        log.info("Banco de usuários: {}", arquivo);
    }

    private boolean colunaExiste(String tabela, String coluna) {
        try (Connection c = conectar(); ResultSet rs = c.createStatement().executeQuery("PRAGMA table_info(" + tabela + ")")) {
            while (rs.next()) {
                if (coluna.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de usuários: " + e.getMessage(), e);
        }
    }

    private static String gravar(Set<String> permissoes) {
        return permissoes == null ? null : String.join(",", permissoes);
    }

    private static Set<String> ler(String texto) {
        if (texto == null) {
            return null;
        }
        Set<String> s = new LinkedHashSet<>();
        for (String p : texto.split(",")) {
            if (!p.isBlank()) {
                s.add(p.trim());
            }
        }
        return s;
    }

    private Connection conectar() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void executar(String sql, Object... params) {
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de usuários: " + e.getMessage(), e);
        }
    }

    private List<Usuario> consultar(String sql, Object... params) {
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<Usuario> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(new Usuario(rs.getLong("id"), rs.getString("login"), rs.getString("nome"),
                            rs.getString("senha_hash"), Perfil.valueOf(rs.getString("perfil")), rs.getInt("ativo") == 1,
                            rs.getInt("trocar_senha") == 1, rs.getString("criado_em"), rs.getString("ultimo_acesso"),
                            ler(rs.getString("permissoes"))));
                }
            }
            return lista;
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de usuários: " + e.getMessage(), e);
        }
    }

    private static String agora() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public long contar() {
        return contarLinhas("SELECT COUNT(*) FROM usuario");
    }

    public long contarAdminsAtivos() {
        return contarLinhas("SELECT COUNT(*) FROM usuario WHERE perfil = 'ADMIN' AND ativo = 1");
    }

    private long contarLinhas(String sql) {
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Erro no banco de usuários: " + e.getMessage(), e);
        }
    }

    public List<Usuario> listar() {
        return consultar("SELECT * FROM usuario ORDER BY nome COLLATE NOCASE");
    }

    public Optional<Usuario> porLogin(String login) {
        return consultar("SELECT * FROM usuario WHERE login = ?", login).stream().findFirst();
    }

    public Optional<Usuario> porId(long id) {
        return consultar("SELECT * FROM usuario WHERE id = ?", id).stream().findFirst();
    }

    public void inserir(String login, String nome, String senhaHash, Perfil perfil, boolean trocarSenha, Set<String> permissoes) {
        executar("INSERT INTO usuario (login, nome, senha_hash, perfil, ativo, trocar_senha, criado_em, permissoes) VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
                login, nome, senhaHash, perfil.name(), trocarSenha ? 1 : 0, agora(), gravar(permissoes));
    }

    public void atualizar(long id, String nome, Perfil perfil, boolean ativo, Set<String> permissoes) {
        executar("UPDATE usuario SET nome = ?, perfil = ?, ativo = ?, permissoes = ? WHERE id = ?",
                nome, perfil.name(), ativo ? 1 : 0, gravar(permissoes), id);
    }

    public void definirSenha(long id, String senhaHash, boolean trocarSenha) {
        executar("UPDATE usuario SET senha_hash = ?, trocar_senha = ? WHERE id = ?", senhaHash, trocarSenha ? 1 : 0, id);
    }

    public void registrarAcesso(long id) {
        executar("UPDATE usuario SET ultimo_acesso = ? WHERE id = ?", agora(), id);
    }

    public void excluir(long id) {
        executar("DELETE FROM usuario WHERE id = ?", id);
    }
}
