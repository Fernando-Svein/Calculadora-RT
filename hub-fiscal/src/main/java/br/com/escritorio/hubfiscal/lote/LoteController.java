package br.com.escritorio.hubfiscal.lote;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.escritorio.hubfiscal.auditoria.AuditoriaExcel;
import br.com.escritorio.hubfiscal.auditoria.AuditoriaService;
import br.com.escritorio.hubfiscal.auditoria.ResultadoAuditoria;
import br.com.escritorio.hubfiscal.classificacao.ClassificacaoExcel;
import br.com.escritorio.hubfiscal.classificacao.ClassificacaoService;
import br.com.escritorio.hubfiscal.classificacao.ResultadoClassificacao;
import br.com.escritorio.hubfiscal.empresa.EmpresaService;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.infra.ResultadoStore;
import br.com.escritorio.hubfiscal.seguranca.UsuarioSessao;
import jakarta.servlet.http.HttpServletResponse;

/** Processamentos em lote a partir de arquivos (SPED, XML, ZIP). */
@RestController
@RequestMapping("/hub/lote")
public class LoteController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoteController.class);

    private static final MediaType XLSX = MediaType
            .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ImportacaoService importacao;
    private final ClassificacaoService classificacao;
    private final AuditoriaService auditoria;
    private final ResultadoStore store;
    private final EmpresaService empresas;

    public LoteController(ImportacaoService importacao, ClassificacaoService classificacao, AuditoriaService auditoria,
            ResultadoStore store, EmpresaService empresas) {
        this.importacao = importacao;
        this.classificacao = classificacao;
        this.auditoria = auditoria;
        this.store = store;
        this.empresas = empresas;
    }

    /** Cabeçalho com o código da lista de empresas dos arquivos que não estão no cadastro (a tela pergunta). */
    static final String CABECALHO_EMPRESAS = "X-Hub-Empresas-Encontradas";

    /**
     * Separa as empresas dos arquivos que ainda não estão no cadastro; nada é gravado: a tela pergunta ao usuário quais
     * adicionar. Vai num cabeçalho (e não no corpo) para não mudar o resultado do lote; uma falha aqui não impede o
     * processamento.
     */
    private void separarEmpresas(Importacao imp, UsuarioSessao usuario, HttpServletResponse resposta) {
        try {
            empresas.guardarEncontradas(imp, usuario.id()).ifPresent(t -> resposta.setHeader(CABECALHO_EMPRESAS, t));
        } catch (RuntimeException e) {
            log.warn("Separação das empresas dos arquivos falhou: {}", e.getMessage());
        }
    }

    /** Classificação de cClassTrib a partir de SPED/XML. */
    @PostMapping("/classificacao")
    public ResultadoClassificacao classificar(@RequestParam("arquivos") List<MultipartFile> arquivos,
            @RequestParam(required = false) LocalDate data, @AuthenticationPrincipal UsuarioSessao usuario,
            HttpServletResponse resposta) {
        Importacao imp = importacao.importar(arquivos);
        separarEmpresas(imp, usuario, resposta);
        ResultadoClassificacao r = classificacao.classificar(imp, data == null ? LocalDate.now() : data);
        r.id = store.guardar(r, usuario.id());
        return r;
    }

    @GetMapping("/classificacao/{id}/excel")
    public ResponseEntity<byte[]> excelClassificacao(@PathVariable String id, @AuthenticationPrincipal UsuarioSessao usuario) {
        ResultadoClassificacao r = store.obter(id, ResultadoClassificacao.class, usuario.id());
        return arquivo(ClassificacaoExcel.gerar(r), "classificacao-cclasstrib");
    }

    /** Auditoria do grupo IBS/CBS das notas (recalcula na calculadora oficial). */
    @PostMapping("/auditoria")
    public ResultadoAuditoria auditar(@RequestParam("arquivos") List<MultipartFile> arquivos,
            @AuthenticationPrincipal UsuarioSessao usuario, HttpServletResponse resposta) {
        Importacao imp = importacao.importar(arquivos);
        separarEmpresas(imp, usuario, resposta);
        ResultadoAuditoria r = auditoria.auditar(imp);
        r.id = store.guardar(r, usuario.id());
        return r;
    }

    @GetMapping("/auditoria/{id}/excel")
    public ResponseEntity<byte[]> excelAuditoria(@PathVariable String id, @AuthenticationPrincipal UsuarioSessao usuario) {
        ResultadoAuditoria r = store.obter(id, ResultadoAuditoria.class, usuario.id());
        return arquivo(AuditoriaExcel.gerar(r), "auditoria-ibs-cbs");
    }

    static ResponseEntity<byte[]> arquivo(byte[] bytes, String prefixo) {
        String nome = prefixo + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(XLSX);
        h.setContentDisposition(ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(h).body(bytes);
    }
}
