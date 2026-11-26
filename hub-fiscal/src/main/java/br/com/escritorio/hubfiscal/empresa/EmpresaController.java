package br.com.escritorio.hubfiscal.empresa;

import java.util.List;

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

    public EmpresaController(EmpresaService empresas) {
        this.empresas = empresas;
    }

    @GetMapping
    public List<Empresa> listar() {
        return empresas.listar();
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
