package br.com.escritorio.hubfiscal.catalogo;

import java.util.List;

/**
 * Resultado da análise de um NCM/NBS: validade da nomenclatura, Imposto
 * Seletivo e classificações candidatas.
 *
 * @param tipo                 NCM ou NBS
 * @param codigo               código analisado
 * @param data                 data de referência (vigência)
 * @param tipoDfe              modelo de documento considerado (55, 65, 91...)
 * @param valida               se o código existe e está vigente na data
 * @param mensagem             explicação quando inválido
 * @param descricao            descrição do item da nomenclatura
 * @param descricaoCompleta    descrição hierárquica (capítulo › posição › ...)
 * @param lc116                item da LC 116 (somente NBS)
 * @param impostoSeletivo      se está sujeito ao Imposto Seletivo
 * @param especificas          classificações vinculadas por anexo (produto/serviço)
 * @param situacionais         classificações sem anexo que dependem da atividade/operação
 *                             (listadas para NBS; para NCM ficam implícitas)
 * @param observacoes          alertas para o analista (ex.: combustível monofásico)
 */
public record AnaliseNomenclatura(
        String tipo,
        String codigo,
        String data,
        Integer tipoDfe,
        boolean valida,
        String mensagem,
        String descricao,
        String descricaoCompleta,
        String lc116,
        boolean impostoSeletivo,
        List<Candidata> especificas,
        List<Candidata> situacionais,
        List<String> observacoes) {
}
