package br.com.escritorio.hubfiscal.auditoria;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.importacao.DocumentoFiscal;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.J;

/**
 * Auditoria do grupo IBS/CBS das notas: para cada documento, envia os itens à
 * calculadora oficial do Regime Geral e compara base, alíquotas e valores
 * calculados com os informados no XML.
 * <p>
 * São feitas duas chamadas por documento: (1) com a base informada no XML,
 * para conferir alíquotas, valores e a validade da classificação; (2) com os
 * componentes do item (vProd, frete, descontos, ICMS, PIS/COFINS...), para
 * conferir a própria base de cálculo.
 */
@Service
public class AuditoriaService {

    public static final Map<String, String> ROTULOS = new LinkedHashMap<>();
    static {
        ROTULOS.put("DIVERGENTE", "Divergente");
        ROTULOS.put("ERRO_CLASSIFICACAO", "Erro de classificação");
        ROTULOS.put("OK", "OK");
        ROTULOS.put("SEM_GRUPO", "Sem grupo IBS/CBS");
        ROTULOS.put("NAO_AUDITADO", "Não auditado");
    }

    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");
    private static final Set<String> CRT_SIMPLES = Set.of("1", "2", "4");

    private static final Map<String, String> UF_IBGE = Map.ofEntries(
            Map.entry("11", "RO"), Map.entry("12", "AC"), Map.entry("13", "AM"), Map.entry("14", "RR"),
            Map.entry("15", "PA"), Map.entry("16", "AP"), Map.entry("17", "TO"), Map.entry("21", "MA"),
            Map.entry("22", "PI"), Map.entry("23", "CE"), Map.entry("24", "RN"), Map.entry("25", "PB"),
            Map.entry("26", "PE"), Map.entry("27", "AL"), Map.entry("28", "SE"), Map.entry("29", "BA"),
            Map.entry("31", "MG"), Map.entry("32", "ES"), Map.entry("33", "RJ"), Map.entry("35", "SP"),
            Map.entry("41", "PR"), Map.entry("42", "SC"), Map.entry("43", "RS"), Map.entry("50", "MS"),
            Map.entry("51", "MT"), Map.entry("52", "GO"), Map.entry("53", "DF"));

    private final ApiReceitaClient api;
    private final HubProperties props;

    public AuditoriaService(ApiReceitaClient api, HubProperties props) {
        this.api = api;
        this.props = props;
    }

    public ResultadoAuditoria auditar(Importacao imp) {
        ResultadoAuditoria r = new ResultadoAuditoria();
        r.geradoEm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        r.arquivos.putAll(imp.arquivos);
        r.avisos.addAll(imp.avisos);
        if (!imp.speds.isEmpty()) {
            r.avisos.add("Arquivos SPED não são usados na auditoria (ela confere o grupo IBS/CBS dos XMLs).");
        }
        r.totalDocumentos = imp.documentos.size();

        Semaphore sem = new Semaphore(props.paralelismo());
        List<List<LinhaAuditoria>> porDocumento = new ArrayList<>();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<LinhaAuditoria>>> fs = new ArrayList<>();
            for (DocumentoFiscal d : imp.documentos) {
                fs.add(ex.submit(() -> {
                    sem.acquireUninterruptibly();
                    try {
                        return auditarDocumento(d);
                    } finally {
                        sem.release();
                    }
                }));
            }
            for (Future<List<LinhaAuditoria>> f : fs) {
                try {
                    porDocumento.add(f.get());
                } catch (Exception e) {
                    throw new IllegalStateException("Falha na auditoria: " + e.getMessage(), e);
                }
            }
        }
        porDocumento.forEach(r.linhas::addAll);
        r.linhas.sort(Comparator.comparing((LinhaAuditoria l) -> ordem(l.status))
                .thenComparing(l -> l.emissao == null ? "" : l.emissao)
                .thenComparing(l -> l.numero == null ? "" : l.numero)
                .thenComparing(l -> l.nItem == null ? 0 : l.nItem));
        r.totalItens = r.linhas.size();
        ROTULOS.keySet().forEach(s -> r.contagemStatus.put(s, 0));
        BigDecimal xCbs = BigDecimal.ZERO, cCbs = BigDecimal.ZERO, xIbs = BigDecimal.ZERO, cIbs = BigDecimal.ZERO;
        for (LinhaAuditoria l : r.linhas) {
            l.statusRotulo = ROTULOS.getOrDefault(l.status, l.status);
            r.contagemStatus.merge(l.status, 1, Integer::sum);
            if ("OK".equals(l.status) || "DIVERGENTE".equals(l.status)) {
                xCbs = xCbs.add(nz(l.xmlVCBS));
                cCbs = cCbs.add(nz(l.calcVCBS));
                xIbs = xIbs.add(nz(l.xmlVIBSUF)).add(nz(l.xmlVIBSMun));
                cIbs = cIbs.add(nz(l.calcVIBSUF)).add(nz(l.calcVIBSMun));
            }
        }
        r.totais.put("xmlVCBS", xCbs);
        r.totais.put("calcVCBS", cCbs);
        r.totais.put("xmlVIBS", xIbs);
        r.totais.put("calcVIBS", cIbs);
        return r;
    }

    private static int ordem(String s) {
        int i = 0;
        for (String k : ROTULOS.keySet()) {
            if (k.equals(s)) {
                return i;
            }
            i++;
        }
        return 99;
    }

    private List<LinhaAuditoria> auditarDocumento(DocumentoFiscal d) {
        List<LinhaAuditoria> linhas = new ArrayList<>();
        Map<Integer, LinhaAuditoria> porItem = new HashMap<>();
        List<ItemFiscal> auditaveis = new ArrayList<>();
        boolean simples = d.emitenteCrt != null && CRT_SIMPLES.contains(d.emitenteCrt);
        for (ItemFiscal it : d.itens) {
            LinhaAuditoria l = novaLinha(d, it);
            linhas.add(l);
            if (d.situacao != null) {
                l.status = "NAO_AUDITADO";
                l.mensagens.add(d.situacao);
            } else if (!it.temGrupoIbsCbs()) {
                l.status = "SEM_GRUPO";
                l.mensagens.add(simples
                        ? "Emitente do Simples Nacional (CRT " + d.emitenteCrt + "): grupo IBS/CBS ausente."
                        : "Item sem o grupo IBSCBS no XML.");
            } else if (simples) {
                l.status = "NAO_AUDITADO";
                l.mensagens.add("Emitente do Simples Nacional (CRT " + d.emitenteCrt
                        + "): confira pela Calculadora do Simples Nacional.");
            } else if (d.municipioFatoGerador == null || d.dataHoraEmissao == null) {
                l.status = "NAO_AUDITADO";
                l.mensagens.add("Documento sem município do fato gerador ou data de emissão.");
            } else {
                auditaveis.add(it);
                porItem.put(it.numeroItem, l);
            }
        }
        if (auditaveis.isEmpty()) {
            return linhas;
        }
        int ano = Integer.parseInt(d.dataHoraEmissao.substring(0, 4));

        // (1) cálculo com a base informada no XML
        Map<String, Object> op1 = operacao(d, auditaveis, ano, true);
        ApiResposta r1 = api.postJson(Api.RG, "/calculadora/observabilidade/regime-geral", op1);
        if (!r1.ok()) {
            String msg = mensagemErro(r1);
            for (LinhaAuditoria l : porItem.values()) {
                l.status = "ERRO_CLASSIFICACAO";
                l.mensagens.add("Calculadora rejeitou o documento: " + msg);
            }
            return linhas;
        }
        Map<String, Object> resp1 = api.jsonObjeto(r1);
        for (Object obj : J.list(resp1, "objetos")) {
            Integer n = J.num(obj, "nObj") == null ? null : J.num(obj, "nObj").intValue();
            LinhaAuditoria l = porItem.get(n);
            if (l == null) {
                continue;
            }
            String estado = J.str(obj, "estadoItem");
            if (!"CALCULADO".equals(estado) && !"CALCULO_SIMULADO".equals(estado)) {
                l.status = "ERRO_CLASSIFICACAO";
                for (Object e : J.list(obj, "erros")) {
                    l.mensagens.add(J.str(e, "code") + " – " + J.str(e, "detail"));
                }
                if (l.mensagens.isEmpty()) {
                    l.mensagens.add("Item não calculado (" + estado + ").");
                }
                continue;
            }
            Object g = J.get(obj, "tribCalc", "IBSCBS", "gIBSCBS");
            l.calcPCBS = J.num(g, "gCBS", "pCBS");
            l.calcVCBS = J.num(g, "gCBS", "vCBS");
            l.calcPIBSUF = J.num(g, "gIBSUF", "pIBSUF");
            l.calcVIBSUF = J.num(g, "gIBSUF", "vIBSUF");
            l.calcPIBSMun = J.num(g, "gIBSMun", "pIBSMun");
            l.calcVIBSMun = J.num(g, "gIBSMun", "vIBSMun");
            l.memoriaCalculo = J.str(obj, "tribCalc", "IBSCBS", "memoriaCalculo", "cbs");
            l.status = "OK";
            if ("CALCULO_SIMULADO".equals(estado)) {
                l.mensagens.add("Cálculo simulado com as alíquotas do próprio XML (a partir de 2027 não há "
                        + "alíquota de referência na base oficial).");
            }
        }

        // (2) base de cálculo a partir dos componentes (somente NF-e/NFC-e)
        boolean temComponentes = auditaveis.stream().anyMatch(i -> !i.componentes.isEmpty());
        if (d.modelo != null && (d.modelo == 55 || d.modelo == 65) && temComponentes) {
            Map<String, Object> op2 = operacao(d, auditaveis, ano, false);
            ApiResposta r2 = api.postJson(Api.RG, "/calculadora/observabilidade/regime-geral", op2);
            if (r2.ok()) {
                for (Object obj : J.list(api.jsonObjeto(r2), "objetos")) {
                    Integer n = J.num(obj, "nObj") == null ? null : J.num(obj, "nObj").intValue();
                    LinhaAuditoria l = porItem.get(n);
                    if (l != null) {
                        l.calcVBCComponentes = J.num(obj, "tribCalc", "IBSCBS", "gIBSCBS", "vBC");
                    }
                }
            }
        }

        // comparação
        for (LinhaAuditoria l : porItem.values()) {
            if (!"OK".equals(l.status)) {
                continue;
            }
            comparar(l, "Base (componentes)", l.xmlVBC, l.calcVBCComponentes);
            comparar(l, "pCBS", l.xmlPCBS, l.calcPCBS);
            comparar(l, "vCBS", l.xmlVCBS, l.calcVCBS);
            comparar(l, "pIBSUF", l.xmlPIBSUF, l.calcPIBSUF);
            comparar(l, "vIBSUF", l.xmlVIBSUF, l.calcVIBSUF);
            comparar(l, "pIBSMun", l.xmlPIBSMun, l.calcPIBSMun);
            comparar(l, "vIBSMun", l.xmlVIBSMun, l.calcVIBSMun);
            if (!l.diferencas.isEmpty()) {
                l.status = "DIVERGENTE";
            }
        }
        return linhas;
    }

    private static void comparar(LinhaAuditoria l, String campo, BigDecimal xml, BigDecimal calc) {
        if (calc == null) {
            return;
        }
        BigDecimal x = xml == null ? BigDecimal.ZERO : xml;
        if (x.subtract(calc).abs().compareTo(TOLERANCIA) > 0) {
            l.diferencas.add(campo + ": XML " + x.toPlainString() + " × calculado " + calc.toPlainString());
        }
    }

    private Map<String, Object> operacao(DocumentoFiscal d, List<ItemFiscal> itens, int ano, boolean baseDoXml) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("id", d.chave == null ? "doc-" + d.numero : d.chave);
        op.put("versao", "1.0.0");
        op.put("dhFatoGerador", d.dataHoraEmissao);
        op.put("municipio", d.municipioFatoGerador);
        op.put("uf", UF_IBGE.getOrDefault(String.valueOf(d.municipioFatoGerador).substring(0, 2), d.emitenteUf));
        if (d.modelo != null) {
            op.put("tpDoc", d.modelo);
        }
        List<Map<String, Object>> lista = new ArrayList<>();
        for (ItemFiscal it : itens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("numero", it.numeroItem);
            if (it.nbs != null) {
                m.put("nbs", it.nbs);
            } else if (it.ncm != null) {
                m.put("ncm", it.ncm);
            }
            m.put("cst", it.cstAtual());
            m.put("cClassTrib", it.cClassTribAtual());
            if (it.quantidade != null) {
                m.put("quantidade", it.quantidade);
            }
            if (it.unidade != null) {
                m.put("unidade", it.unidade);
            }
            BigDecimal vbc = dec(it.ibscbs.get("vBC"));
            if (baseDoXml || it.componentes.isEmpty()) {
                m.put("baseCalculo", vbc != null ? vbc : it.valor);
            } else {
                m.put("gComponentesBC", it.componentes);
            }
            if (ano >= 2027) {
                Map<String, Object> aliq = new LinkedHashMap<>();
                aliq.put("cbs", nz(dec(it.ibscbs.get("pCBS"))));
                aliq.put("ibsEstadual", nz(dec(it.ibscbs.get("pIBSUF"))));
                aliq.put("ibsMunicipal", nz(dec(it.ibscbs.get("pIBSMun"))));
                m.put("aliquotasNominais", aliq);
            }
            lista.add(m);
        }
        op.put("itens", lista);
        return op;
    }

    private String mensagemErro(ApiResposta r) {
        Object j = api.json(r);
        StringBuilder sb = new StringBuilder();
        String t = J.str(j, "title");
        String det = J.str(j, "detail");
        sb.append(t == null ? "HTTP " + r.status() : t);
        if (det != null) {
            sb.append(" – ").append(det);
        }
        for (Object e : J.list(j, "erros")) {
            sb.append(" | ").append(J.str(e, "code")).append(": ").append(J.str(e, "detail"));
        }
        for (Object e : J.list(j, "errors")) {
            sb.append(" | ").append(J.str(e, "detail"));
        }
        return sb.toString();
    }

    private static LinhaAuditoria novaLinha(DocumentoFiscal d, ItemFiscal it) {
        LinhaAuditoria l = new LinhaAuditoria();
        l.arquivo = d.arquivo;
        l.origem = d.origem;
        l.chave = d.chave;
        l.numero = d.numero;
        l.emissao = d.dataHoraEmissao == null ? null : d.dataHoraEmissao.substring(0, Math.min(10, d.dataHoraEmissao.length()));
        l.emitenteCnpj = d.emitenteCnpj;
        l.emitenteNome = d.emitenteNome;
        l.emitenteCrt = d.emitenteCrt;
        l.destinatario = (d.destinatarioCnpj == null ? "" : d.destinatarioCnpj + " ")
                + (d.destinatarioNome == null ? "" : d.destinatarioNome);
        l.nItem = it.numeroItem;
        l.codigo = it.codigo;
        l.descricao = it.descricao;
        l.ncmNbs = it.nbs != null ? it.nbs : it.ncm;
        l.cfop = it.cfop;
        l.cst = it.cstAtual();
        l.cClassTrib = it.cClassTribAtual();
        l.xmlVBC = dec(it.ibscbs.get("vBC"));
        l.xmlPCBS = dec(it.ibscbs.get("pCBS"));
        l.xmlVCBS = dec(it.ibscbs.get("vCBS"));
        l.xmlPIBSUF = dec(it.ibscbs.get("pIBSUF"));
        l.xmlVIBSUF = dec(it.ibscbs.get("vIBSUF"));
        l.xmlPIBSMun = dec(it.ibscbs.get("pIBSMun"));
        l.xmlVIBSMun = dec(it.ibscbs.get("vIBSMun"));
        return l;
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
