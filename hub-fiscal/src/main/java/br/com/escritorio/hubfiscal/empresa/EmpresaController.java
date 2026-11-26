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
