package br.com.escritorio.hubfiscal.classificacao;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import br.com.escritorio.hubfiscal.catalogo.Candidata;
import br.com.escritorio.hubfiscal.excel.Planilha;
import br.com.escritorio.hubfiscal.excel.Planilha.Coluna;
import br.com.escritorio.hubfiscal.excel.VendasExcel;

/** Gera a planilha Excel da classificação em lote. */
public final class ClassificacaoExcel {

    /** Cor de fundo (RGB) da coluna Status. */
    private static final Map<StatusClassificacao, String> CORES = new EnumMap<>(Map.of(
            StatusClassificacao.DIVERGENTE, "F8B4B4",
            StatusClassificacao.REVISAR, "FCE7A6",
            StatusClassificacao.SUGERIDO, "CDEBD2",
            StatusClassificacao.CONFERIDO, "8FD19E",
            StatusClassificacao.INVALIDO, "E5C1E5",
            StatusClassificacao.SEM_NOMENCLATURA, "D9D9D9"));

    private ClassificacaoExcel() {
    }

    /** Planilha com as abas Resumo, Classificação e Alternativas por item. */
    public static byte[] gerar(ResultadoClassificacao r) {
        try (Planilha p = new Planilha()) {
            CORES.forEach((s, cor) -> p.corStatus(s.rotulo(), cor));

            List<String[]> resumo = new ArrayList<>();
            resumo.add(new String[] { "Data de referência (vigência)", r.dataReferencia });
            resumo.add(new String[] { "Gerado em", r.geradoEm });
            resumo.add(new String[] { "Total de itens analisados", String.valueOf(r.totalItens) });
            if (!r.contribuintes.isEmpty()) {
                resumo.add(new String[] { "Contribuinte(s) (SPED)", String.join("\n", r.contribuintes) });
            }
            if (r.conferenciaOficial != null) {
                resumo.add(new String[] { "Conferência na calculadora oficial", r.conferenciaOficial });
            }
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Resultado por status" });
            r.contagemStatus.forEach((s, n) -> resumo.add(new String[] {
                    StatusClassificacao.valueOf(s).rotulo(), String.valueOf(n) }));
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Legenda" });
            resumo.add(new String[] { "Divergente", "A classificação usada nas notas é inválida para o NCM/NBS, para o "
                    + "documento ou para o CST, ou foi rejeitada pela calculadora oficial. Corrigir." });
            resumo.add(new String[] { "Revisar", "Existe benefício possível ou mais de uma opção. Exige decisão do "
                    + "analista (requisitos legais, adquirente, operação)." });
            resumo.add(new String[] { "Sugerido", "Nenhum benefício vinculado ao produto: sugestão de tributação "
                    + "integral (000001) para venda normal." });
            resumo.add(new String[] { "Conferido", "A classificação usada nas notas coincide com a sugestão." });
            resumo.add(new String[] { "NCM/NBS inválido", "Código inexistente, incompleto ou fora de vigência." });
            resumo.add(new String[] { "Sem NCM/NBS", "Item sem nomenclatura (comum em serviços no SPED)." });
            resumo.add(new String[] { "" });
            resumo.add(new String[] { "Calculadora oficial", "Cada cClassTrib (o das notas e o sugerido) foi enviado "
                    + "à calculadora da Receita com o NCM/NBS, o CST e o modelo do documento. \"Aceita\" significa que "
                    + "ela calcula a combinação; \"Rejeitada\" traz o código e o motivo do erro oficial (ex.: REG-011). "
                    + "Aceitar não confirma o direito ao benefício: os requisitos do anexo continuam com o analista." });
            resumo.add(new String[] { "Importante", "As sugestões usam as tabelas oficiais da calculadora da Receita "
                    + "(anexos da LC 214/2025). Benefícios podem exigir requisitos que não constam dos arquivos "
                    + "(destinatário, finalidade, regime). A decisão final é do responsável técnico. A coluna "
                    + "\"O que revisar\" diz o que está divergente ou precisa de decisão em cada item." });
            VendasExcel.resumo(resumo, r.vendasPorNcm);
            Planilha.secoesArquivosEAvisos(resumo, r.arquivos, r.avisos);
            p.abaResumo("Resumo", "Classificação tributária (cClassTrib)", resumo);

            List<Coluna> cols = List.of(
                    Coluna.texto("Status", 16), Coluna.texto("O que revisar", 60), Coluna.texto("CNPJ", 17),
                    Coluna.texto("Empresa", 28), Coluna.texto("Código do item", 16),
                    Coluna.texto("Descrição do item", 40), Coluna.texto("Tipo do item (SPED)", 22),
                    Coluna.texto("Nomenclatura", 8), Coluna.texto("NCM/NBS", 11),
                    Coluna.texto("Descrição NCM/NBS", 50), Coluna.texto("Imposto Seletivo", 9),
                    Coluna.texto("Origem", 22), Coluna.texto("CFOPs de saída", 14), Coluna.moeda("Valor total"),
                    Coluna.texto("CST atual", 8), Coluna.texto("cClassTrib atual", 10),
                    Coluna.texto("Calculadora oficial (atual)", 30),
                    Coluna.texto("CST sugerido", 8), Coluna.texto("cClassTrib sugerido", 10),
                    Coluna.texto("Calculadora oficial (sugerido)", 30),
                    Coluna.texto("Descrição do cClassTrib sugerido", 45), Coluna.texto("Tratamento", 20),
                    Coluna.texto("Anexo LC 214/2025", 45), Coluna.texto("Fundamentação", 30),
                    Coluna.texto("Alternativas do anexo", 30),
                    Coluna.texto("Outras possibilidades (dependem da atividade)", 40),
                    Coluna.texto("Observações", 60));
            List<Object[]> linhas = new ArrayList<>();
            for (LinhaClassificacao l : r.linhas) {
                linhas.add(new Object[] { l.statusRotulo, l.revisar, l.donoCnpj, l.donoNome, l.codigo, l.descricao,
                        l.tipoItemSped, l.tipoNomenclatura, l.nomenclatura, l.descricaoNomenclatura,
                        l.impostoSeletivo ? "Sim" : "", l.origens, l.cfopsSaida, l.valorTotal, l.cstAtual,
                        l.cClassTribAtual, l.oficialAtual, l.cstSugerido, l.cClassTribSugerido, l.oficialSugerido,
                        l.descricaoSugerido,
                        l.tratamentoSugerido, l.anexoSugerido, l.fundamentacaoSugerido,
                        l.alternativas.stream().map(c -> c.cClassTrib() + (c.condicional() ? "*" : ""))
                                .collect(Collectors.joining(", ")),
                        String.join("\n", l.outrasPossibilidades), String.join("\n", l.observacoes) });
            }
            p.abaTabela("Classificação", cols, linhas, 0);

            List<Coluna> colsAlt = List.of(Coluna.texto("Código do item", 16), Coluna.texto("Descrição do item", 40),
                    Coluna.texto("NCM/NBS", 11), Coluna.texto("cClassTrib", 10), Coluna.texto("CST", 6),
                    Coluna.texto("Descrição", 55), Coluna.texto("Tratamento", 22), Coluna.texto("Categoria", 32),
                    Coluna.texto("Condicional", 10), Coluna.texto("Admitido no documento", 10),
                    Coluna.texto("Anexo", 6), Coluna.texto("Item", 6), Coluna.texto("Texto do anexo", 60),
                    Coluna.texto("Fundamentação", 40));
            List<Object[]> alt = new ArrayList<>();
            for (LinhaClassificacao l : r.linhas) {
                for (Candidata c : l.alternativas) {
                    alt.add(new Object[] { l.codigo, l.descricao, l.nomenclatura, c.cClassTrib(), c.cst(),
                            c.descricao(), c.reducaoResumo(), c.categoria().rotulo(),
                            c.condicional() ? "Sim" : "Não", c.dfeCompativel() ? "Sim" : "Não", c.anexo(),
                            c.itemAnexo(), c.textoAnexo(), c.fundamentacao() });
                }
            }
            p.abaTabela("Alternativas por item", colsAlt, alt, -1);
            VendasExcel.aba(p, r.vendasPorNcm);
            return p.bytes();
        }
    }
}
