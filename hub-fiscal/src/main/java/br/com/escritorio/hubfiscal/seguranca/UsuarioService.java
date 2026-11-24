package br.com.escritorio.hubfiscal.seguranca;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.seguranca.Usuario.Perfil;

/** Regras de usuários: autenticação, bloqueio por tentativas, política de senha e administração. */
@Service
public class UsuarioService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    /** Tentativas erradas seguidas antes do bloqueio temporário. */
    static final int MAX_TENTATIVAS = 5;
    static final Duration BLOQUEIO = Duration.ofMinutes(15);

    private static final String ALFABETO_PROVISORIA = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepositorio repo;
    private final PasswordEncoder encoder;
    /** Hash usado quando o login não existe, para o tempo de resposta não revelar se o usuário existe. */
    private final String hashFicticio;

    private record Falhas(int quantidade, Instant bloqueadoAte) {
    }

    private final Map<String, Falhas> falhas = new ConcurrentHashMap<>();

    public UsuarioService(UsuarioRepositorio repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
        this.hashFicticio = encoder.encode("senha-ficticia-para-tempo-constante");
    }

    /** Resultado de uma tentativa de login. */
    public sealed interface ResultadoLogin {
        record Sucesso(Usuario usuario) implements ResultadoLogin {
        }

        record Falha(String mensagem, int status) implements ResultadoLogin {
        }
    }

    public ResultadoLogin autenticar(String loginInformado, String senha, String ip) {
        String login = normalizarLogin(loginInformado);
        if (login.isEmpty() || senha == null || senha.isEmpty()) {
            return new ResultadoLogin.Falha("Informe usuário e senha.", 400);
        }
        Falhas f = falhas.get(login);
        if (f != null && f.bloqueadoAte() != null && Instant.now().isBefore(f.bloqueadoAte())) {
            long min = Math.max(1, Duration.between(Instant.now(), f.bloqueadoAte()).toMinutes() + 1);
            log.warn("Login bloqueado (tentativas excedidas): {} de {}", login, ip);
            return new ResultadoLogin.Falha("Muitas tentativas erradas. Acesso bloqueado por " + min + " minuto(s).", 429);
        }
        Optional<Usuario> u = repo.porLogin(login);
        boolean senhaOk = encoder.matches(senha, u.map(Usuario::senhaHash).orElse(hashFicticio));
        if (u.isEmpty() || !senhaOk) {
            int qtd = (f == null || f.bloqueadoAte() != null ? 0 : f.quantidade()) + 1;
            falhas.put(login, new Falhas(qtd, qtd >= MAX_TENTATIVAS ? Instant.now().plus(BLOQUEIO) : null));
            log.warn("Login recusado: {} de {} (tentativa {})", login, ip, qtd);
            int restantes = MAX_TENTATIVAS - qtd;
            return new ResultadoLogin.Falha(restantes > 0
                    ? "Usuário ou senha inválidos."
                    : "Usuário ou senha inválidos. Acesso bloqueado por " + BLOQUEIO.toMinutes() + " minutos.", 401);
        }
        if (!u.get().ativo()) {
            log.warn("Login de usuário desativado: {} de {}", login, ip);
            return new ResultadoLogin.Falha("Usuário desativado. Procure o administrador do hub.", 403);
        }
        falhas.remove(login);
        repo.registrarAcesso(u.get().id());
        log.info("Login: {} de {}", login, ip);
        return new ResultadoLogin.Sucesso(repo.porId(u.get().id()).orElseThrow());
    }

    // ---------------------------------------------------------------- senhas

    static String normalizarLogin(String login) {
        return login == null ? "" : login.trim().toLowerCase();
    }

    /** Política: mínimo de 8 caracteres, com letras e números, diferente do login. */
    static void validarSenha(String senha, String login) {
        if (senha == null || senha.length() < 8) {
            throw new ErroNegocio("A senha deve ter pelo menos 8 caracteres.");
        }
        if (senha.length() > 72) {
            throw new ErroNegocio("A senha deve ter no máximo 72 caracteres.");
        }
        if (!senha.matches(".*\\p{L}.*") || !senha.matches(".*\\d.*")) {
            throw new ErroNegocio("A senha deve conter letras e números.");
        }
        if (login != null && senha.equalsIgnoreCase(login)) {
            throw new ErroNegocio("A senha não pode ser igual ao usuário.");
        }
    }

    static String gerarProvisoria() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(ALFABETO_PROVISORIA.charAt(RANDOM.nextInt(ALFABETO_PROVISORIA.length())));
        }
        // garante letra e número
        return sb.append(RANDOM.nextInt(10)).toString();
    }

    public void trocarPropriaSenha(long id, String senhaAtual, String novaSenha) {
        Usuario u = repo.porId(id).orElseThrow(() -> new ErroNegocio("Usuário não encontrado."));
        if (senhaAtual == null || !encoder.matches(senhaAtual, u.senhaHash())) {
            throw new ErroNegocio("A senha atual está incorreta.");
        }
        validarSenha(novaSenha, u.login());
        if (encoder.matches(novaSenha, u.senhaHash())) {
            throw new ErroNegocio("A nova senha deve ser diferente da atual.");
        }
        repo.definirSenha(id, encoder.encode(novaSenha), false);
        log.info("Senha alterada pelo próprio usuário: {}", u.login());
    }

    // ---------------------------------------------------------------- primeiro acesso

    public boolean semUsuarios() {
        return repo.contar() == 0;
    }

    public synchronized Usuario criarPrimeiroAdmin(String login, String nome, String senha) {
        if (!semUsuarios()) {
            throw new ErroNegocio("O administrador inicial já foi criado.");
        }
        String l = validarLogin(login);
        validarSenha(senha, l);
        repo.inserir(l, validarNome(nome), encoder.encode(senha), Perfil.ADMIN, false, null);
        log.info("Administrador inicial criado: {}", l);
        return repo.porLogin(l).orElseThrow();
    }

    // ---------------------------------------------------------------- administração

    public List<Usuario> listar() {
        return repo.listar();
    }

    public Optional<Usuario> porId(long id) {
        return repo.porId(id);
    }

    /** Cria o usuário com senha provisória (troca obrigatória no primeiro acesso). Devolve a senha provisória. */
    public String criar(String login, String nome, Perfil perfil, java.util.Collection<String> permissoes, String admin) {
        String l = validarLogin(login);
        if (repo.porLogin(l).isPresent()) {
            throw new ErroNegocio("Já existe um usuário com o login '" + l + "'.");
        }
        String provisoria = gerarProvisoria();
        repo.inserir(l, validarNome(nome), encoder.encode(provisoria), perfil == null ? Perfil.USUARIO : perfil, true, validarPermissoes(permissoes));
        log.info("Usuário criado: {} ({}) por {}", l, perfil, admin);
        return provisoria;
    }

    public void atualizar(long id, String nome, Perfil perfil, boolean ativo, java.util.Collection<String> permissoes, long idAdmin) {
        Usuario u = repo.porId(id).orElseThrow(() -> new ErroNegocio("Usuário não encontrado."));
        if (id == idAdmin && (!ativo || perfil != Perfil.ADMIN)) {
            throw new ErroNegocio("Você não pode desativar nem retirar o perfil de administrador da sua própria conta.");
        }
        boolean deixaDeSerAdminAtivo = u.perfil() == Perfil.ADMIN && u.ativo() && (perfil != Perfil.ADMIN || !ativo);
        if (deixaDeSerAdminAtivo && repo.contarAdminsAtivos() <= 1) {
            throw new ErroNegocio("É preciso manter ao menos um administrador ativo.");
        }
        repo.atualizar(id, validarNome(nome), perfil, ativo, validarPermissoes(permissoes));
        if (ativo) {
            falhas.remove(u.login());
        }
        log.info("Usuário alterado: {} -> perfil {}, ativo {}, ferramentas {}", u.login(), perfil, ativo, permissoes == null ? "todas" : permissoes);
    }

    public String redefinirSenha(long id, String admin) {
        Usuario u = repo.porId(id).orElseThrow(() -> new ErroNegocio("Usuário não encontrado."));
        String provisoria = gerarProvisoria();
        repo.definirSenha(id, encoder.encode(provisoria), true);
        falhas.remove(u.login());
        log.info("Senha redefinida: {} por {}", u.login(), admin);
        return provisoria;
    }

    public void excluir(long id, long idAdmin) {
        Usuario u = repo.porId(id).orElseThrow(() -> new ErroNegocio("Usuário não encontrado."));
        if (id == idAdmin) {
            throw new ErroNegocio("Você não pode excluir a sua própria conta.");
        }
        if (u.perfil() == Perfil.ADMIN && u.ativo() && repo.contarAdminsAtivos() <= 1) {
            throw new ErroNegocio("É preciso manter ao menos um administrador ativo.");
        }
        repo.excluir(id);
        log.info("Usuário excluído: {}", u.login());
    }

    /**
     * Ferramentas liberadas: null = todas (inclui as que forem criadas no futuro).
     * Marcar todas também grava "todas". Ids desconhecidos são recusados.
     */
    private static java.util.Set<String> validarPermissoes(java.util.Collection<String> permissoes) {
        if (permissoes == null) {
            return null;
        }
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (String p : permissoes) {
            if (!Ferramentas.IDS.contains(p)) {
                throw new ErroNegocio("Ferramenta desconhecida: " + p);
            }
            s.add(p);
        }
        return s.containsAll(Ferramentas.IDS) ? null : s;
    }

    private static String validarLogin(String login) {
        String l = normalizarLogin(login);
        if (!l.matches("[a-z0-9._-]{3,40}")) {
            throw new ErroNegocio("Login inválido: use de 3 a 40 caracteres (letras sem acento, números, ponto, hífen ou sublinhado).");
        }
        return l;
    }

    private static String validarNome(String nome) {
        String n = nome == null ? "" : nome.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw new ErroNegocio("Informe o nome (até 100 caracteres).");
        }
        return n;
    }
}
