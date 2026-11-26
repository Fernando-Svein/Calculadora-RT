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
import br.com.escritorio.hubfiscal.seguranca.UsuarioSessao;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

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
            @RequestParam(required = false) LocalDate data, @AuthenticationPrincipal UsuarioSessao usuario) {
        Importacao imp = importacao.importar(arquivos);
        String encontradas = null;
        try {
            // mesma regra dos lotes: nada é cadastrado sem o usuário confirmar
            encontradas = empresas.guardarEncontradas(imp, usuario.id()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Separação das empresas dos arquivos falhou: {}", e.getMessage());
        }
        return ImportacaoSimuladorService.resposta(conversor.converter(imp, data == null ? LocalDate.now() : data), imp, encontradas);
    }
}
