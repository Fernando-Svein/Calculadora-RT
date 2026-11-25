package br.com.escritorio.hubfiscal.seguranca;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identificação do usuário logado guardada na sessão.
 *
 * @param marcaSenha resumo curto do hash da senha no momento do login: se a senha mudar, as sessões
 *                   abertas com a senha antiga deixam de valer (ver SegurancaConfig.UsuarioAtivoFilter)
 */
public record UsuarioSessao(long id, String login, String nome, String perfil, String marcaSenha) implements Serializable {

    public static UsuarioSessao de(Usuario u) {
        return new UsuarioSessao(u.id(), u.login(), u.nome(), u.perfil().name(), marcaSenha(u.senhaHash()));
    }

    /** SHA-256 do hash BCrypt, truncado: identifica a versão da senha sem guardar o hash na sessão. */
    public static String marcaSenha(String senhaHash) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(senhaHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
