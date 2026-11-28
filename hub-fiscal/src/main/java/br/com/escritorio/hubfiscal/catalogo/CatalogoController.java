package br.com.escritorio.hubfiscal.catalogo;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroParametro;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Nomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;

/** Consultas do catálogo tributário: NCM/NBS, busca na nomenclatura e detalhe de cClassTrib. */
@RestController
@RequestMapping("/hub")
public class CatalogoController {

    private final CatalogoService catalogo;
    private final BaseOficial base;

    public CatalogoController(CatalogoService catalogo, BaseOficial base) {
        this.catalogo = catalogo;
        this.base = base;
    }

    private static LocalDate dataOuHoje(LocalDate data) {
        return data == null ? LocalDate.now() : data;
    }

    /** Classificações possíveis para um NCM (bens). */
    @GetMapping("/ncm/{ncm}")
    public AnaliseNomenclatura ncm(@PathVariable String ncm, @RequestParam(required = false) LocalDate data,
            @RequestParam(required = false) Integer tipoDfe) {
        return catalogo.analisar(Tipo.NCM, ncm, dataOuHoje(data), tipoDfe);
    }

    /** Classificações possíveis para uma NBS (serviços). */
    @GetMapping("/nbs/{nbs}")
    public AnaliseNomenclatura nbs(@PathVariable String nbs, @RequestParam(required = false) LocalDate data,
            @RequestParam(required = false) Integer tipoDfe) {
        return catalogo.analisar(Tipo.NBS, nbs, dataOuHoje(data), tipoDfe);
    }

    /** Busca de NCM/NBS por código (prefixo) ou por texto da descrição. */
    @GetMapping("/busca/{tipo}")
    public List<Nomenclatura> buscar(@PathVariable String tipo, @RequestParam String q,
            @RequestParam(required = false) LocalDate data, @RequestParam(defaultValue = "60") int limite) {
        Tipo t = switch (tipo.toLowerCase(Locale.ROOT)) {
            case "ncm" -> Tipo.NCM;
            case "nbs" -> Tipo.NBS;
            default -> throw new ErroParametro("Tipo de busca inválido: use ncm ou nbs.");
        };
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        // limite entre 1 e 300 (no SQLite, LIMIT negativo significa "sem limite")
        return base.buscar(t, q, dataOuHoje(data), Math.max(1, Math.min(limite, 300)));
    }

    /** Detalhe de uma classificação, com os NCM/NBS do anexo. */
    @GetMapping("/classificacao/{cClassTrib}")
    public Map<String, Object> classificacao(@PathVariable String cClassTrib,
            @RequestParam(required = false) LocalDate data) {
        return catalogo.detalheClassificacao(cClassTrib, dataOuHoje(data));
    }
}
