package br.com.escritorio.hubfiscal.seguranca;

/**
 * Usuário do hub.
 *
 * @param id           identificador
 * @param login        nome de acesso (minúsculo, único)
 * @param nome         nome de exibição
 * @param senhaHash    senha com BCrypt (nunca sai do servidor)
 * @param perfil       ADMIN ou USUARIO
 * @param ativo        conta liberada para acesso
 * @param trocarSenha  obriga a trocar a senha no próximo acesso (senha provisória)
 * @param criadoEm     data/hora de criação (ISO)
 * @param ultimoAcesso data/hora do último login (ISO) ou null
 * @param permissoes   ferramentas liberadas (ids de {@link Ferramentas}); null = todas
 */
public record Usuario(
        long id,
        String login,
        String nome,
        String senhaHash,
        Perfil perfil,
        boolean ativo,
        boolean trocarSenha,
        String criadoEm,
        String ultimoAcesso,
        java.util.Set<String> permissoes) {

    /** Ferramentas efetivamente liberadas (administrador e "null" = todas). */
    public java.util.Set<String> ferramentas() {
        if (perfil == Perfil.ADMIN || permissoes == null) {
            return Ferramentas.IDS;
        }
        var s = new java.util.LinkedHashSet<String>();
        Ferramentas.IDS.stream().filter(permissoes::contains).forEach(s::add);
        return s;
    }

    public boolean pode(java.util.Set<String> algumaDestas) {
        var f = ferramentas();
        return algumaDestas.stream().anyMatch(f::contains);
    }

    public enum Perfil {
        ADMIN("Administrador"),
        USUARIO("Usuário");

        private final String rotulo;

        Perfil(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /** Versão segura para enviar às telas (sem o hash da senha). */
    public java.util.Map<String, Object> publico() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("login", login);
        m.put("nome", nome);
        m.put("perfil", perfil.name());
        m.put("perfilRotulo", perfil.rotulo());
        m.put("ativo", ativo);
        m.put("trocarSenha", trocarSenha);
        m.put("criadoEm", criadoEm);
        m.put("ultimoAcesso", ultimoAcesso);
        m.put("permissoes", java.util.List.copyOf(ferramentas()));
        m.put("acessoTotal", ferramentas().size() == Ferramentas.IDS.size());
        return m;
    }
}
