package br.com.escritorio.hubfiscal.classificacao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import br.com.escritorio.hubfiscal.catalogo.BaseOficial.Tipo;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;

/** Um produto/serviço juntado ao longo de todos os arquivos (cadastro e movimentos do SPED, itens dos XMLs). */
final class Agregado {

    /** Nomenclatura analisada para um item: NCM ou NBS, código e modelo de documento predominante. */
    record ChaveAnalise(Tipo tipo, String codigo, int modelo) {
    }

    String donoCnpj;
    String donoNome;
    String codigo;
    String descricao;
    String tipoItem;
    final Set<String> ncms = new LinkedHashSet<>();
    final Set<String> nbss = new LinkedHashSet<>();
    final Set<String> origens = new TreeSet<>();
    final Set<String> cfopsSaida = new TreeSet<>();
    final Set<String> cfopsEntrada = new TreeSet<>();
    final Map<Integer, Integer> modelos = new HashMap<>();
    final Map<String, Integer> cClassTribAtual = new HashMap<>();
    final Map<String, Integer> cstAtual = new HashMap<>();
    int ocorrencias;
    BigDecimal valor = BigDecimal.ZERO;

    void absorver(ItemFiscal it) {
        if (descricao == null && it.descricao != null) {
            descricao = it.descricao;
        }
        if (tipoItem == null && it.tipoItemSped != null) {
            tipoItem = it.tipoItemSped;
        }
        if (donoNome == null) {
            donoNome = it.donoNome;
        }
        if (it.ncm != null) {
            ncms.add(it.ncm);
        }
        if (it.nbs != null) {
            nbss.add(it.nbs);
        }
        boolean cadastro = "0200".equals(it.registro);
        origens.add(it.origem + (cadastro ? " (cadastro)" : ""));
        if (cadastro) {
            return;
        }
        ocorrencias++;
        if (it.valor != null) {
            valor = valor.add(it.valor);
        }
        if (it.cfop != null) {
            if (Cfops.saida(it.cfop)) {
                cfopsSaida.add(it.cfop);
            } else if (Cfops.entrada(it.cfop)) {
                cfopsEntrada.add(it.cfop);
            }
        }
        if (it.modelo != null && (it.modelo == 55 || it.modelo == 65 || it.modelo == 91)) {
            modelos.merge(it.modelo, 1, Integer::sum);
        }
        if (it.cClassTribAtual() != null) {
            cClassTribAtual.merge(it.cClassTribAtual(), 1, Integer::sum);
        }
        if (it.cstAtual() != null) {
            cstAtual.merge(it.cstAtual(), 1, Integer::sum);
        }
    }

    /** NBS tem prioridade sobre NCM; sem modelo nas notas, assume NFS-e (91) para NBS e NF-e (55) para NCM. */
    ChaveAnalise chaveAnalise() {
        Integer modelo = maisFrequente(modelos);
        if (!nbss.isEmpty()) {
            return new ChaveAnalise(Tipo.NBS, nbss.iterator().next(), modelo == null ? 91 : modelo);
        }
        if (!ncms.isEmpty()) {
            return new ChaveAnalise(Tipo.NCM, ncms.iterator().next(), modelo == null ? 55 : modelo);
        }
        return null;
    }

    /** Junta cadastro e movimentos do SPED e itens dos XMLs por contribuinte + código do item. */
    static Map<String, Agregado> agregar(Importacao imp) {
        Map<String, Agregado> agregados = new LinkedHashMap<>();
        List<ItemFiscal> todos = new ArrayList<>();
        todos.addAll(imp.cadastroSped());
        todos.addAll(imp.movimentosSped());
        todos.addAll(imp.itensXml());
        for (ItemFiscal it : todos) {
            if (it.codigo == null && it.ncm == null && it.nbs == null) {
                continue;
            }
            String dono = it.donoCnpj == null ? "" : it.donoCnpj;
            String chave = dono + "|" + (it.codigo != null ? it.codigo : "NCM:" + it.ncm + "|" + it.descricao);
            agregados.computeIfAbsent(chave, k -> {
                Agregado n = new Agregado();
                n.donoCnpj = it.donoCnpj;
                n.codigo = it.codigo;
                return n;
            }).absorver(it);
        }
        return agregados;
    }

    static <K> K maisFrequente(Map<K, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
}
