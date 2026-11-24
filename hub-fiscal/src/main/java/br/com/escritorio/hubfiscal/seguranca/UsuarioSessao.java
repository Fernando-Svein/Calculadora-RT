package br.com.escritorio.hubfiscal.seguranca;

import java.io.Serializable;

/** Identificação do usuário logado guardada na sessão. */
public record UsuarioSessao(long id, String login, String nome, String perfil) implements Serializable {

    public static UsuarioSessao de(Usuario u) {
        return new UsuarioSessao(u.id(), u.login(), u.nome(), u.perfil().name());
    }
}
