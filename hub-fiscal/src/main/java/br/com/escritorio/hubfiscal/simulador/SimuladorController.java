package br.com.escritorio.hubfiscal.simulador;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.escritorio.hubfiscal.empresa.EmpresaService;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;

/** Importação de XML/SPED para o Simulador do Regime Regular (ferramenta "calc-rg", ver Ferramentas). */
@RestController
public class SimuladorController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SimuladorController.class);

    private final ImportacaoService importacao;
    private final ImportacaoSimuladorService conversor;
    private final EmpresaService empresas;

    public SimuladorController(ImportacaoService importacao, ImportacaoSimuladorService conversor, EmpresaService empresas) {
        this.importacao = importacao;
        this.conversor = conversor;
        this.empresas = empresas;
    }

    @PostMapping("/hub/simulador/importar")
    public Map<String, Object> importar(@RequestParam("arquivos") List<MultipartFile> arquivos,
            @RequestParam(required = false) LocalDate data) {
        Importacao imp = importacao.importar(arquivos);
        int novas = 0;
        try {
            novas = empresas.cadastrarDaImportacao(imp).size(); // mesma regra dos lotes
        } catch (RuntimeException e) {
            log.warn("Cadastro automático de empresas falhou: {}", e.getMessage());
        }
        return ImportacaoSimuladorService.resposta(conversor.converter(imp, data == null ? LocalDate.now() : data), imp, novas);
    }
}
