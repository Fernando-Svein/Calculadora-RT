package br.com.escritorio.hubfiscal.excel;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.excel.Planilha.Coluna;

/**
 * Exportação genérica para Excel: as telas enviam uma tabela já montada
 * (consultas, resultados das calculadoras) e recebem um .xlsx.
 */
@RestController
public class ExportacaoController {

    /** Pedido de exportação. tipo da coluna: texto, moeda, numero, percentual. */
    public record PedidoExportacao(String titulo, String arquivo, List<ColunaPedido> colunas, List<List<Object>> linhas,
            List<List<String>> resumo) {
    }

    public record ColunaPedido(String titulo, String tipo, Integer largura) {
    }

    @PostMapping("/hub/excel")
    public ResponseEntity<byte[]> exportar(@RequestBody PedidoExportacao pedido) {
        try (Planilha p = new Planilha()) {
            if (pedido.resumo() != null && !pedido.resumo().isEmpty()) {
                List<String[]> pares = new ArrayList<>();
                for (List<String> par : pedido.resumo()) {
                    pares.add(par.toArray(String[]::new));
                }
                p.abaResumo("Resumo", pedido.titulo() == null ? "Hub Fiscal" : pedido.titulo(), pares);
            }
            List<Coluna> cols = new ArrayList<>();
            for (ColunaPedido c : pedido.colunas()) {
                Planilha.Tipo t = switch (c.tipo() == null ? "texto" : c.tipo().toLowerCase()) {
                    case "moeda" -> Planilha.Tipo.MOEDA;
                    case "numero" -> Planilha.Tipo.NUMERO;
                    case "percentual" -> Planilha.Tipo.PERCENTUAL;
                    default -> Planilha.Tipo.TEXTO;
                };
                int largura = c.largura() != null ? c.largura() : Math.max(10, Math.min(60, c.titulo().length() + 4));
                cols.add(new Coluna(c.titulo(), largura, t));
            }
            List<Object[]> linhas = new ArrayList<>();
            for (List<Object> l : pedido.linhas()) {
                linhas.add(l.toArray());
            }
            p.abaTabela("Dados", cols, linhas, -1);
            String base = pedido.arquivo() == null ? "hub-fiscal" : pedido.arquivo().replaceAll("[^\\w.-]", "_");
            String nome = base + "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            h.setContentDisposition(ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build());
            return ResponseEntity.ok().headers(h).body(p.bytes());
        }
    }
}
