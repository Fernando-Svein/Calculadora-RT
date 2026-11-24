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
import java.util.List;
import java.util.Optional;

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
        log.info("Banco de usuários: {}", arquivo);
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
                            rs.getInt("trocar_senha") == 1, rs.getString("criado_em"), rs.getString("ultimo_acesso")));
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
        return consultar("SELECT * FROM usuario").size();
    }

    public long contarAdminsAtivos() {
        return consultar("SELECT * FROM usuario WHERE perfil = 'ADMIN' AND ativo = 1").size();
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

    public void inserir(String login, String nome, String senhaHash, Perfil perfil, boolean trocarSenha) {
        executar("INSERT INTO usuario (login, nome, senha_hash, perfil, ativo, trocar_senha, criado_em) VALUES (?, ?, ?, ?, 1, ?, ?)",
                login, nome, senhaHash, perfil.name(), trocarSenha ? 1 : 0, agora());
    }

    public void atualizar(long id, String nome, Perfil perfil, boolean ativo) {
        executar("UPDATE usuario SET nome = ?, perfil = ?, ativo = ? WHERE id = ?", nome, perfil.name(), ativo ? 1 : 0, id);
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
