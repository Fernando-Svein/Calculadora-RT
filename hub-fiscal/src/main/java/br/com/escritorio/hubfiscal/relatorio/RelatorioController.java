package br.com.escritorio.hubfiscal.relatorio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.relatorio.RelatorioPdf.Coluna;
import br.com.escritorio.hubfiscal.seguranca.UsuarioSessao;

/**
 * Relatório PDF de uma simulação individual. A tela envia o que já calculou (empresa, resumo e tabela) e
 * recebe o PDF, como na exportação para Excel ({@code /hub/excel}). Não recalcula nada.
 */
@RestController
public class RelatorioController {

    /** Pedido de relatório: mesmos campos da exportação para Excel, mais a empresa e as observações. */
    public record PedidoRelatorio(String titulo, String arquivo, List<String> empresa, List<List<String>> resumo,
            List<Coluna> colunas, List<List<Object>> linhas, List<String> observacoes) {
    }

    private final Path arquivoLogo;

    public RelatorioController(HubProperties props) {
        this.arquivoLogo = Path.of(props.arquivoLogo());
    }

    @PostMapping("/hub/relatorio/pdf")
    public ResponseEntity<byte[]> pdf(@RequestBody PedidoRelatorio p, @AuthenticationPrincipal UsuarioSessao usuario) {
        if (p.titulo() == null || p.titulo().isBlank()) {
            throw new IllegalArgumentException("Informe o título do relatório.");
        }
        int colunas = p.colunas() == null ? 0 : p.colunas().size();
        int linhas = p.linhas() == null ? 0 : p.linhas().size();
        int resumo = p.resumo() == null ? 0 : p.resumo().size();
        if (colunas > 40 || linhas > 5_000 || resumo > 200 || (p.observacoes() != null && p.observacoes().size() > 100)
                || (p.empresa() != null && p.empresa().size() > 10)) {
            throw new IllegalArgumentException("Relatório grande demais para PDF (máximo de 40 colunas e 5 mil linhas). Use o Excel.");
        }
        byte[] logo = null;
        try {
            if (Files.isRegularFile(arquivoLogo)) {
                logo = Files.readAllBytes(arquivoLogo);
            }
        } catch (Exception e) {
            // sem logo
        }
        String agora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        byte[] pdf = RelatorioPdf.gerar(new RelatorioPdf.Dados(p.titulo().trim(), agora, usuario == null ? null : usuario.nome(),
                p.empresa(), p.resumo(), p.colunas(), p.linhas(), p.observacoes(), logo));
        String base = p.arquivo() == null || p.arquivo().isBlank() ? "relatorio-hub-fiscal" : p.arquivo().replaceAll("[^\\w.-]", "_");
        String nome = base + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".pdf";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_PDF);
        h.setContentDisposition(ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(h).body(pdf);
    }
}
