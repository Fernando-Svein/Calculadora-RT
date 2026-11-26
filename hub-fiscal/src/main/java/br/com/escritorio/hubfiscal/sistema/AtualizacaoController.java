package br.com.escritorio.hubfiscal.sistema;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.seguranca.UsuarioSessao;

/** Botão "Atualizar agora" da Situação do sistema (só administradores: rota /admin, ver SegurancaConfig). */
@RestController
public class AtualizacaoController {

    private final AtualizacaoManual atualizacao;

    public AtualizacaoController(AtualizacaoManual atualizacao) {
        this.atualizacao = atualizacao;
    }

    @PostMapping("/admin/sistema/atualizar-calculadoras")
    public ResponseEntity<Map<String, Object>> atualizar(@AuthenticationPrincipal UsuarioSessao admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(atualizacao.solicitar(admin.login()));
    }
}
