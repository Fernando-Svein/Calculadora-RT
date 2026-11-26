package br.com.escritorio.hubfiscal.empresa;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.empresa.EmpresaService.DadosEmpresa;
import br.com.escritorio.hubfiscal.seguranca.UsuarioSessao;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Cadastro de empresas. A consulta é aberta a todo usuário logado (os simuladores listam as empresas no
 * seletor); incluir, alterar e excluir exigem a ferramenta "Empresas" (ver Ferramentas).
 */
@RestController
@RequestMapping("/hub/empresas")
public class EmpresaController {

    private final EmpresaService empresas;
    private final ConsultaCnpjService consulta;

    public EmpresaController(EmpresaService empresas, ConsultaCnpjService consulta) {
        this.empresas = empresas;
        this.consulta = consulta;
    }

    @GetMapping
    public List<Empresa> listar() {
        return empresas.listar();
    }

    /** Cadastro público do CNPJ (API externa configurada em hub.consulta-cnpj-url); exige a ferramenta "Empresas". */
    @GetMapping("/consulta-cnpj/{cnpj}")
    public Map<String, Object> consultarCnpj(@PathVariable String cnpj) {
        return consulta.consultar(cnpj);
    }

    /** Empresas encontradas numa importação e ainda não cadastradas (a tela pergunta quais adicionar). */
    @GetMapping("/encontradas/{token}")
    public List<EmpresaService.EmpresaEncontrada> encontradas(@PathVariable String token, @AuthenticationPrincipal UsuarioSessao usuario) {
        return empresas.encontradasGuardadas(token, usuario.id());
    }

    public record Escolha(List<String> cnpjs) {
    }

    /** Adiciona as escolhidas, cada uma com a consulta completa do CNPJ; exige a ferramenta "Empresas" (POST /hub/empresas...). */
    @PostMapping("/encontradas/{token}")
    public List<EmpresaService.ResultadoCadastro> adicionarEncontradas(@PathVariable String token, @RequestBody Escolha escolha,
            @AuthenticationPrincipal UsuarioSessao usuario) {
        return empresas.cadastrarEncontradas(token, escolha == null ? null : escolha.cnpjs(), usuario.id(), consulta);
    }

    public record Selecao(List<Long> ids) {
    }

    /** Exclusão das empresas selecionadas na lista. */
    @PostMapping("/excluir")
    public Map<String, Object> excluirSelecionadas(@RequestBody Selecao selecao) {
        return Map.of("excluidas", empresas.excluirVarias(selecao == null ? null : selecao.ids()));
    }

    /** A tela mostra o botão "Consultar CNPJ" só quando a consulta está ligada. */
    @GetMapping("/consulta-cnpj")
    public Map<String, Object> consultaDisponivel() {
        return Map.of("disponivel", consulta.disponivel());
    }

    @GetMapping("/{id}")
    public Empresa obter(@PathVariable long id) {
        return empresas.obter(id);
    }

    @PostMapping
    public Empresa criar(@RequestBody DadosEmpresa dados) {
        return empresas.criar(dados);
    }

    @PutMapping("/{id}")
    public Empresa alterar(@PathVariable long id, @RequestBody DadosEmpresa dados) {
        return empresas.atualizar(id, dados);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable long id) {
        empresas.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
