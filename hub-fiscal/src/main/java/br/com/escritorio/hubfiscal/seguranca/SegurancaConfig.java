package br.com.escritorio.hubfiscal.seguranca;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Autenticação do hub: sessão no servidor (cookie HttpOnly), proteção CSRF para a SPA,
 * telas e APIs fechadas para quem não fez login e área /admin só para administradores.
 */
@Configuration
@EnableWebSecurity
public class SegurancaConfig {

    /** Endereços acessíveis sem login. */
    private static final String[] PUBLICOS = {
            "/login.html", "/css/**", "/js/tema.js", "/js/login.js", "/js/marca.js", "/favicon.ico", "/error",
            "/marca", "/marca/logo",
            "/auth/csrf", "/auth/estado", "/auth/login", "/auth/primeiro-acesso" };

    /**
     * Content-Security-Policy: scripts, estilos e conexões só do próprio hub. As telas não usam atributos style
     * (usam as classes de css/utilitarios.css); estilos ajustados por script (ex.: largura da barra de progresso)
     * passam pelo CSSOM, que a política permite.
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'self'";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Desliga o usuário padrão gerado pelo Spring Boot: o login é feito pelo AuthController. */
    @Bean
    UserDetailsService userDetailsService() {
        return login -> {
            throw new UsernameNotFoundException(login);
        };
    }

    @Bean
    SecurityFilterChain filtroSeguranca(HttpSecurity http, SecurityContextRepository contexto, UsuarioRepositorio repo)
            throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLICOS).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(c -> c.spa())
                .securityContext(s -> s.securityContextRepository(contexto))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(l -> l.logoutUrl("/auth/logout")
                        .logoutSuccessHandler((req, resp, auth) -> resp.setStatus(204))
                        .deleteCookies("HUBSESSION"))
                .exceptionHandling(e -> e.authenticationEntryPoint(naoAutenticado()).accessDeniedHandler(acessoNegado()))
                // impede que outros sites embutam o hub (clickjacking); permite molduras do próprio hub.
                // CSP: só executa scripts dos arquivos do próprio hub (nada inline nem de outros sites),
                // segunda barreira caso algum texto vindo de XML/SPED escape do tratamento das telas.
                .headers(h -> h
                        .frameOptions(fo -> fo.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
                .addFilterBefore(new UsuarioAtivoFilter(repo, contexto), AuthorizationFilter.class);
        return http.build();
    }

    /** Telas: redireciona para o login. APIs: 401 em JSON (a tela trata e manda para o login). */
    private static AuthenticationEntryPoint naoAutenticado() {
        return (req, resp, ex) -> {
            String accept = Optional.ofNullable(req.getHeader("Accept")).orElse("");
            boolean pagina = "GET".equals(req.getMethod()) && accept.contains("text/html");
            if (pagina) {
                resp.sendRedirect(req.getContextPath() + "/login.html");
            } else {
                json(resp, 401, "Sessão encerrada", "Faça login para continuar.", null);
            }
        };
    }

    private static AccessDeniedHandler acessoNegado() {
        return (req, resp, ex) -> {
            boolean csrf = ex instanceof org.springframework.security.web.csrf.CsrfException;
            json(resp, 403, csrf ? "Sessão desatualizada" : "Acesso negado",
                    csrf ? "Recarregue a página e tente novamente." : "Seu perfil não tem permissão para esta operação.",
                    csrf ? "CSRF" : null);
        };
    }

    static void json(HttpServletResponse resp, int status, String titulo, String detalhe, String codigo) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/problem+json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String c = codigo == null ? "" : ",\"codigo\":\"" + codigo + "\"";
        resp.getWriter().write("{\"status\":" + status + ",\"title\":\"" + titulo + "\",\"detail\":\"" + detalhe + "\"" + c + "}");
    }

    /**
     * A cada requisição confere no banco se o usuário logado continua ativo e com o mesmo perfil
     * (desativar ou mudar o perfil tem efeito imediato). Enquanto a senha for provisória,
     * só libera as rotas de autenticação.
     */
    static class UsuarioAtivoFilter extends OncePerRequestFilter {

        private final UsuarioRepositorio repo;
        private final SecurityContextRepository contexto;

        UsuarioAtivoFilter(UsuarioRepositorio repo, SecurityContextRepository contexto) {
            this.repo = repo;
            this.contexto = contexto;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws ServletException, IOException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UsuarioSessao s) {
                Optional<Usuario> atual = repo.porId(s.id());
                if (atual.isEmpty() || !atual.get().ativo()) {
                    encerrar(req);
                    json(resp, 401, "Sessão encerrada", "Seu acesso foi desativado ou removido.", null);
                    return;
                }
                Usuario u = atual.get();
                if (!UsuarioSessao.marcaSenha(u.senhaHash()).equals(s.marcaSenha())) {
                    // a senha mudou depois deste login (troca pelo próprio usuário em outro computador ou
                    // redefinição pelo administrador): encerra as demais sessões abertas com a senha antiga
                    encerrar(req);
                    json(resp, 401, "Sessão encerrada", "A senha foi alterada. Entre novamente.", null);
                    return;
                }
                if (!u.perfil().name().equals(s.perfil()) || !u.nome().equals(s.nome())) {
                    // perfil/nome alterados pelo administrador: atualiza a sessão
                    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                    ctx.setAuthentication(autenticacao(u));
                    SecurityContextHolder.setContext(ctx);
                    contexto.saveContext(ctx, req, resp);
                }
                // mesmo caminho que o proxy repassa (decodificado e normalizado): ver Ferramentas.caminhoCanonico
                String caminho = Ferramentas.caminhoCanonico(req);
                String minusculo = caminho.toLowerCase(java.util.Locale.ROOT);
                boolean api = minusculo.startsWith("/hub/") || minusculo.startsWith("/proxy/") || minusculo.startsWith("/admin/");
                if (u.trocarSenha() && api) {
                    json(resp, 403, "Troca de senha obrigatória", "Defina uma nova senha para continuar.", "TROCAR_SENHA");
                    return;
                }
                // ferramentas liberadas para o usuário (vale na hora em que o administrador altera)
                var exigidas = Ferramentas.exigidas(req.getMethod(), caminho);
                if (exigidas.isPresent() && !u.pode(exigidas.get())) {
                    json(resp, 403, "Ferramenta não liberada",
                            "Seu usuário não tem acesso a esta ferramenta. Peça ao administrador do hub para liberar.", "SEM_PERMISSAO");
                    return;
                }
            }
            chain.doFilter(req, resp);
        }

        private static void encerrar(HttpServletRequest req) {
            SecurityContextHolder.clearContext();
            HttpSession sessao = req.getSession(false);
            if (sessao != null) {
                sessao.invalidate();
            }
        }
    }

    static Authentication autenticacao(Usuario u) {
        return UsernamePasswordAuthenticationToken.authenticated(UsuarioSessao.de(u), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.perfil().name())));
    }
}
