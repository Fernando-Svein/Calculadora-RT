package br.com.escritorio.hubfiscal.seguranca;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.seguranca.Usuario.Perfil;

/** Administração de usuários (somente perfil ADMIN – ver SegurancaConfig). */
@RestController
@RequestMapping("/admin/usuarios")
public class UsuariosController {

    private final UsuarioService usuarios;

    public UsuariosController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    /** permissoes: ids das ferramentas liberadas; ausente/null = todas. */
    public record NovoUsuario(String login, String nome, Perfil perfil, List<String> permissoes) {
    }

    public record Alteracao(String nome, Perfil perfil, boolean ativo, List<String> permissoes) {
    }

    /** Ferramentas que podem ser liberadas por usuário (para a tela montar as caixas de seleção). */
    @GetMapping("/ferramentas")
    public List<Ferramentas.Ferramenta> ferramentas() {
        return Ferramentas.TODAS;
    }

    @GetMapping
    public List<Map<String, Object>> listar() {
        return usuarios.listar().stream().map(Usuario::publico).toList();
    }

    /** Cria com senha provisória, devolvida uma única vez para o administrador repassar. */
    @PostMapping
    public Map<String, Object> criar(@RequestBody NovoUsuario n, @AuthenticationPrincipal UsuarioSessao admin) {
        String provisoria = usuarios.criar(n.login(), n.nome(), n.perfil(), n.permissoes(), admin.login());
        return Map.of("login", UsuarioService.normalizarLogin(n.login()), "senhaProvisoria", provisoria);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> alterar(@PathVariable long id, @RequestBody Alteracao a, @AuthenticationPrincipal UsuarioSessao admin) {
        usuarios.atualizar(id, a.nome(), a.perfil() == null ? Perfil.USUARIO : a.perfil(), a.ativo(), a.permissoes(), admin.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/redefinir-senha")
    public Map<String, Object> redefinirSenha(@PathVariable long id, @AuthenticationPrincipal UsuarioSessao admin) {
        return Map.of("senhaProvisoria", usuarios.redefinirSenha(id, admin.login()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable long id, @AuthenticationPrincipal UsuarioSessao admin) {
        usuarios.excluir(id, admin.id());
        return ResponseEntity.noContent().build();
    }
}
