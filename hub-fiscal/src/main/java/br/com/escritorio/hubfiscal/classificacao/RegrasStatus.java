package br.com.escritorio.hubfiscal.classificacao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.catalogo.AnaliseNomenclatura;
import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.catalogo.Candidata.Categoria;
import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.infra.J;

/**
 * Regras que decidem o status de cada item da classificação (Conferido, Divergente, Revisar, Sugerido) e as
 * observações e o "O que revisar" que o justificam. A conferência na calculadora oficial vem depois
 * ({@link ConferenciaClassificacao}) e pode tornar o item divergente ou levá-lo para revisão.
 */
@Component
class RegrasStatus {

    /** Tributação integral: sugestão quando o produto não tem benefício vinculado. */
    static final String INTEGRAL = "000001";

    private final CatalogoService catalogo;

    RegrasStatus(CatalogoService catalogo) {
        this.catalogo = catalogo;
    }

    /** Decide o status do item e acrescenta as observações que o justificam. */
    StatusClassificacao avaliar(LinhaClassificacao l, Agregado a, AnaliseNomenclatura an,
            List<Candidata> especificas, List<Candidata> principais, Candidata sugerida, Set<String> indicados,
            Map<String, Map<String, Object>> tabela) {
        String atual = l.cClassTribAtual;
        boolean beneficioSugerido = sugerida != null && !INTEGRAL.equals(sugerida.cClassTrib());
        List<Candidata> condicionaisAnexo = especificas.stream().filter(Candidata::condicional).toList();

        if (atual != null) {
            Map<String, Object> regAtual = tabela.get(atual);
            if (regAtual == null) {
                l.revisar = "cClassTrib " + atual + " usado nas notas não existe ou não está vigente em " + an.data()
                        + "." + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            String nomenclaturaAtual = String.valueOf(J.str(regAtual, "nomenclatura"));
            boolean atualViaAnexo = an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(atual));
            if (classificacaoExigeAnexo(atual, an) && !atualViaAnexo) {
                l.revisar = an.tipo() + " " + an.codigo() + " não consta do anexo do cClassTrib " + atual
                        + " (a calculadora oficial rejeita: erro REG-011)." + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            if (!nomenclaturaAtual.contains(an.tipo()) && !nomenclaturaAtual.equals("Não possui")) {
                l.observacoes.add("cClassTrib " + atual + " é de nomenclatura \"" + nomenclaturaAtual + "\".");
            }
            if (!CatalogoService.admiteDocumento(regAtual, an.tipoDfe())) {
                l.revisar = "cClassTrib " + atual + " não é admitido no documento modelo " + an.tipoDfe() + "."
                        + sugestao(sugerida);
                return StatusClassificacao.DIVERGENTE;
            }
            String cstEsperado = atual.substring(0, 3);
            if (l.cstAtual != null && !l.cstAtual.equals(cstEsperado)) {
                l.revisar = "CST " + l.cstAtual + " incompatível com o cClassTrib " + atual + ": corrija o CST para "
                        + cstEsperado + " ou o cClassTrib.";
                return StatusClassificacao.DIVERGENTE;
            }
            if (sugerida != null && atual.equals(sugerida.cClassTrib()) || indicados.contains(atual)) {
                if (beneficioSugerido) {
                    l.observacoes.add("Benefício aplicado – confira se os requisitos legais do anexo são atendidos.");
                }
                return StatusClassificacao.CONFERIDO;
            }
            if (INTEGRAL.equals(atual) && beneficioSugerido) {
                l.revisar = "Possível benefício não aplicado: " + sugerida.cClassTrib() + " ("
                        + sugerida.reducaoResumo() + "). Confira se o produto atende aos requisitos.";
            } else if (atualViaAnexo) {
                l.revisar = "cClassTrib atual (" + atual + ") é válido para o " + an.tipo()
                        + ", mas difere da sugestão principal (" + (sugerida == null ? "-" : sugerida.cClassTrib())
                        + "). Confirme qual se aplica.";
            } else {
                l.revisar = "cClassTrib atual (" + atual + ") é válido, mas depende da operação/adquirente. "
                        + "Sugestão para venda normal: " + (sugerida == null ? "-" : sugerida.cClassTrib()) + ".";
            }
            return StatusClassificacao.REVISAR;
        }

        // sem classificação informada: sugestão
        StatusClassificacao status;
        boolean combustivel = an.observacoes().stream().anyMatch(o -> o.startsWith("Combustível"));
        if (combustivel || sugerida != null && sugerida.categoria() == Categoria.MONOFASICO) {
            status = StatusClassificacao.REVISAR;
            l.revisar = "Combustível em regime monofásico: defina o cClassTrib pela posição na cadeia "
                    + "(620001 produtor/importador, 620006 tributação já cobrada etc.).";
        } else if (beneficioSugerido) {
            status = StatusClassificacao.REVISAR;
            l.revisar = principais.size() > 1
                    ? "Mais de um benefício possível para o produto: " + principais.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ". Escolha conforme os requisitos."
                    : "Benefício vinculado ao produto (" + sugerida.cClassTrib() + ", " + sugerida.reducaoResumo()
                            + "): confira os requisitos legais antes de aplicar.";
        } else {
            status = StatusClassificacao.SUGERIDO;
        }
        if (!condicionaisAnexo.isEmpty()) {
            l.observacoes.add("Há tratamentos condicionais para este " + an.tipo() + " (dependem do adquirente, "
                    + "região ou regime): " + condicionaisAnexo.stream().map(Candidata::cClassTrib)
                            .collect(Collectors.joining(", ")) + ".");
        }
        if (!indicados.isEmpty()) {
            l.observacoes.add("Há saídas com CFOP de operação especial: " + String.join("; ", l.sugestoesPorCfop)
                    + ".");
        }
        if ("07".equals(a.tipoItem) || "08".equals(a.tipoItem)) {
            l.observacoes.add("Item de uso e consumo/ativo: a classificação só é relevante se houver saída.");
        }
        return status;
    }

    /** A classificação exige anexo (tem lista fechada de NCM/NBS) para o tipo analisado? */
    private boolean classificacaoExigeAnexo(String cClassTrib, AnaliseNomenclatura an) {
        // se aparece nas específicas, tem anexo; se aparece nas situacionais, não tem; senão consulta a base
        if (an.especificas().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib))) {
            return true;
        }
        if (an.situacionais().stream().anyMatch(c -> c.cClassTrib().equals(cClassTrib))) {
            return false;
        }
        return catalogo.temAnexo(Tipo.valueOf(an.tipo()), cClassTrib, LocalDate.parse(an.data()));
    }

    /** Complemento de uma divergência: para qual classificação corrigir. */
    private static String sugestao(Candidata sugerida) {
        return sugerida == null ? "" : " Sugestão: " + sugerida.cClassTrib() + " (" + sugerida.reducaoResumo() + ").";
    }
}
