package br.com.escritorio.hubfiscal.classificacao;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.infra.Andamento;

/** Leva para as linhas da classificação a resposta da calculadora oficial ({@link ConferenciaOficial}). */
@Component
class ConferenciaClassificacao {

    private final ConferenciaOficial conferencia;

    ConferenciaClassificacao(ConferenciaOficial conferencia) {
        this.conferencia = conferencia;
    }

    /**
     * Confere na calculadora oficial o cClassTrib das notas e o sugerido de cada item. Rejeição do cClassTrib das notas
     * torna o item divergente; rejeição da sugestão leva o item para revisão. Calculadora fora do ar não interrompe o
     * lote: os itens ficam como "Não conferida" e o resumo avisa.
     */
    void conferir(ResultadoClassificacao res, Importacao imp, LocalDate data, Andamento andamento) {
        Map<String, Integer> municipios = municipiosPorContribuinte(imp);
        int padrao = municipios.values().stream().findFirst().orElse(ConferenciaOficial.MUNICIPIO_PADRAO);
        Map<LinhaClassificacao, ConferenciaOficial.Combinacao> atuais = new HashMap<>();
        Map<LinhaClassificacao, ConferenciaOficial.Combinacao> sugeridas = new HashMap<>();
        for (LinhaClassificacao l : res.linhas) {
            if (l.status == StatusClassificacao.INVALIDO || l.status == StatusClassificacao.SEM_NOMENCLATURA
                    || l.modeloAnalisado == null) {
                continue;
            }
            int municipio = municipios.getOrDefault(l.donoCnpj == null ? "" : l.donoCnpj, padrao);
            if (l.cClassTribAtual != null && l.cClassTribAtual.length() == 6) {
                atuais.put(l, new ConferenciaOficial.Combinacao(l.tipoNomenclatura, l.nomenclatura, l.modeloAnalisado,
                        l.cstAtual != null ? l.cstAtual : l.cClassTribAtual.substring(0, 3), l.cClassTribAtual,
                        municipio));
            }
            if (l.cClassTribSugerido != null && l.cstSugerido != null) {
                sugeridas.put(l, new ConferenciaOficial.Combinacao(l.tipoNomenclatura, l.nomenclatura,
                        l.modeloAnalisado, l.cstSugerido, l.cClassTribSugerido, municipio));
            }
        }
        Set<ConferenciaOficial.Combinacao> todas = new LinkedHashSet<>(atuais.values());
        todas.addAll(sugeridas.values());
        if (todas.isEmpty()) {
            return;
        }
        Map<ConferenciaOficial.Combinacao, ConferenciaOficial.Resultado> r = conferencia.conferir(todas, data, andamento);

        Map<ConferenciaOficial.Situacao, Integer> contagem = new HashMap<>();
        r.values().forEach(x -> contagem.merge(x.situacao(), 1, Integer::sum));
        res.conferenciaOficial = todas.size() + " combinação(ões) NCM/NBS + CST + cClassTrib conferidas na calculadora "
                + "oficial: " + contagem.getOrDefault(ConferenciaOficial.Situacao.ACEITA, 0) + " aceita(s), "
                + contagem.getOrDefault(ConferenciaOficial.Situacao.REJEITADA, 0) + " rejeitada(s), "
                + contagem.getOrDefault(ConferenciaOficial.Situacao.NAO_CONFERIDA, 0) + " não conferida(s).";
        if (contagem.getOrDefault(ConferenciaOficial.Situacao.NAO_CONFERIDA, 0) > 0) {
            res.avisos.add("Parte das classificações não foi conferida na calculadora oficial (veja as colunas "
                    + "\"Calculadora oficial\"). Verifique se a calculadora do Regime Geral está no ar e processe de novo.");
        }

        for (LinhaClassificacao l : res.linhas) {
            ConferenciaOficial.Resultado atual = atuais.containsKey(l) ? r.get(atuais.get(l)) : null;
            ConferenciaOficial.Resultado sugerido = sugeridas.containsKey(l) ? r.get(sugeridas.get(l)) : null;
            if (atual != null) {
                l.oficialAtual = atual.rotulo();
                if (atual.situacao() == ConferenciaOficial.Situacao.REJEITADA
                        && l.status != StatusClassificacao.DIVERGENTE) {
                    l.revisar = "A calculadora oficial rejeitou o cClassTrib " + l.cClassTribAtual + " usado nas notas: "
                            + atual.mensagem() + (l.cClassTribSugerido == null
                                    || l.cClassTribSugerido.equals(l.cClassTribAtual) ? ""
                                            : " Sugestão: " + l.cClassTribSugerido + ".");
                    l.definirStatus(StatusClassificacao.DIVERGENTE);
                } else if (atual.situacao() == ConferenciaOficial.Situacao.ACEITA
                        && l.status == StatusClassificacao.DIVERGENTE) {
                    l.observacoes.add("A calculadora oficial aceitou a combinação usada nas notas; a divergência "
                            + "apontada é da conferência do hub. Confira antes de corrigir.");
                }
            }
            if (sugerido != null) {
                l.oficialSugerido = sugerido.rotulo();
                if (sugerido.situacao() == ConferenciaOficial.Situacao.REJEITADA) {
                    String texto = "A sugestão " + l.cClassTribSugerido + " foi rejeitada pela calculadora oficial: "
                            + sugerido.mensagem() + " Escolha outra classificação (aba \"Alternativas por item\").";
                    if (l.status == StatusClassificacao.SUGERIDO || l.status == StatusClassificacao.CONFERIDO) {
                        l.revisar = texto;
                        l.definirStatus(StatusClassificacao.REVISAR);
                    } else {
                        l.observacoes.add(texto);
                    }
                }
            }
        }
    }

    /** Município (IBGE) de cada contribuinte pelo SPED (registro 0000) ou pelo emitente dos XMLs. */
    private static Map<String, Integer> municipiosPorContribuinte(Importacao imp) {
        Map<String, Integer> m = new LinkedHashMap<>();
        imp.speds.forEach(s -> {
            if (s.cnpj() != null && s.municipio() != null && String.valueOf(s.municipio()).length() == 7) {
                m.putIfAbsent(s.cnpj(), s.municipio());
            }
        });
        imp.documentos.forEach(d -> {
            if (d.emitenteCnpj != null && d.emitenteMunicipio != null
                    && String.valueOf(d.emitenteMunicipio).length() == 7) {
                m.putIfAbsent(d.emitenteCnpj, d.emitenteMunicipio);
            }
        });
        return m;
    }
}
