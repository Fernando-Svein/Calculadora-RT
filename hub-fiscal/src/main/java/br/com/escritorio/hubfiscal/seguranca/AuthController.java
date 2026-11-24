package br.com.escritorio.hubfiscal.seguranca;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Login, sessão, primeiro acesso e troca da própria senha. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UsuarioService usuarios;
    private final SecurityContextRepository contexto;

    public AuthController(UsuarioService usuarios, SecurityContextRepository contexto) {
        this.usuarios = usuarios;
        this.contexto = contexto;
    }

    public record Credenciais(String usuario, String senha) {
    }

    public record PrimeiroAcesso(String usuario, String nome, String senha) {
    }

    public record TrocaSenha(String senhaAtual, String novaSenha) {
    }

    /** Garante o cookie XSRF-TOKEN para a tela poder enviar POSTs. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }

    /** Situação para a tela de login: se ainda não há usuários (primeiro acesso) e se o acesso é do próprio servidor. */
    @GetMapping("/estado")
    public Map<String, Object> estado(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("primeiroAcesso", usuarios.semUsuarios());
        m.put("acessoLocal", local(req));
        return m;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Credenciais c, HttpServletRequest req, HttpServletResponse resp) {
        var r = usuarios.autenticar(c.usuario(), c.senha(), req.getRemoteAddr());
        if (r instanceof UsuarioService.ResultadoLogin.Falha f) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(f.status()), f.mensagem());
            p.setTitle("Não foi possível entrar");
            return ResponseEntity.status(f.status()).body(p);
        }
        Usuario u = ((UsuarioService.ResultadoLogin.Sucesso) r).usuario();
        iniciarSessao(u, req, resp);
        return ResponseEntity.ok(u.publico());
    }

    /** Cria o administrador inicial. Só funciona enquanto não há usuários e a partir do próprio servidor. */
    @PostMapping("/primeiro-acesso")
    public ResponseEntity<?> primeiroAcesso(@RequestBody PrimeiroAcesso p, HttpServletRequest req, HttpServletResponse resp) {
        if (!local(req)) {
            throw new ErroNegocio("Por segurança, o administrador inicial só pode ser criado no próprio servidor do hub "
                    + "(abra http://localhost:8090 nele).");
        }
        Usuario u = usuarios.criarPrimeiroAdmin(p.usuario(), p.nome(), p.senha());
        iniciarSessao(u, req, resp);
        return ResponseEntity.ok(u.publico());
    }

    /** Usuário logado (a tela chama ao abrir; 401 se não houver sessão). */
    @GetMapping("/eu")
    public Map<String, Object> eu(@AuthenticationPrincipal UsuarioSessao s, CsrfToken token) {
        token.getToken(); // renova o cookie XSRF-TOKEN se necessário
        return usuarios.porId(s.id()).map(Usuario::publico)
                .orElseThrow(() -> new ErroNegocio("Usuário não encontrado."));
    }

    @PostMapping("/senha")
    public ResponseEntity<Void> trocarSenha(@AuthenticationPrincipal UsuarioSessao s, @RequestBody TrocaSenha t) {
        usuarios.trocarPropriaSenha(s.id(), t.senhaAtual(), t.novaSenha());
        return ResponseEntity.noContent().build();
    }

    private void iniciarSessao(Usuario u, HttpServletRequest req, HttpServletResponse resp) {
        req.getSession(true);
        req.changeSessionId(); // evita fixação de sessão
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(SegurancaConfig.autenticacao(u));
        SecurityContextHolder.setContext(ctx);
        contexto.saveContext(ctx, req, resp);
    }

    private static boolean local(HttpServletRequest req) {
        try {
            return InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
