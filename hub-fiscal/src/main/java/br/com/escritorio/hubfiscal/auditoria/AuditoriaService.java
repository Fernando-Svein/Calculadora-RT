package br.com.escritorio.hubfiscal.auditoria;

import static br.com.escritorio.hubfiscal.infra.Valores.decimal;
import static br.com.escritorio.hubfiscal.infra.Valores.zeroSeNulo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.catalogo.CatalogoService;
import br.com.escritorio.hubfiscal.cenario.CenarioCbsService;
import br.com.escritorio.hubfiscal.importacao.VendasPorNcm;
import br.com.escritorio.hubfiscal.config.HubProperties;
import br.com.escritorio.hubfiscal.importacao.DocumentoFiscal;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.ItemFiscal;
import br.com.escritorio.hubfiscal.infra.Andamento;
import br.com.escritorio.hubfiscal.infra.Api;
import br.com.escritorio.hubfiscal.infra.ApiReceitaClient;
import br.com.escritorio.hubfiscal.infra.ApiResposta;
import br.com.escritorio.hubfiscal.infra.EndpointsReceita;
import br.com.escritorio.hubfiscal.infra.J;
import br.com.escritorio.hubfiscal.infra.Paralelo;
import br.com.escritorio.hubfiscal.infra.Ufs;
import br.com.escritorio.hubfiscal.infra.Valores;
import tools.jackson.databind.JsonNode;

/**
 * Auditoria do grupo IBS/CBS das notas: para cada documento, envia os itens à
 * calculadora oficial do Regime Geral e compara base, alíquotas e valores
 * calculados com os informados no XML.
 * <p>
 * São feitas duas chamadas por documento: (1) com a base informada no XML,
 * para conferir alíquotas, valores e a validade da classificação; (2) com os
 * componentes do item (vProd, frete, descontos, ICMS, PIS/COFINS...), para
 * conferir a própria base de cálculo.
 * <p>
 * Com o cenário de CBS configurado ({@code hub.cenario-cbs}, ex.: TCU 11,3%), a resposta (1) é reescalada
 * localmente pelo {@link CenarioCbsService} e cada item traz também a CBS do cenário, lado a lado com a oficial.
 * Não há chamada extra à calculadora. O status (OK/Divergente) continua comparando o XML com a alíquota oficial.
 * <p>
 * Itens com data de emissão ou nItem inválidos ficam como "Não auditado", com o motivo, sem interromper o lote.
 */
@Service
public class AuditoriaService {

    /** Diferença máxima aceita entre o XML e o calculado (arredondamento de centavos). */
    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");
    /** CRT do Simples Nacional (1, 2 e 4): auditados pela calculadora do Simples, não por esta. */
    private static final Set<String> CRT_SIMPLES = Set.of("1", "2", "4");
    /** A partir de 2027 a base oficial não tem alíquotas de referência: o cálculo usa as do próprio XML. */
    private static final int ANO_SEM_ALIQUOTA_REFERENCIA = 2027;

    private final ApiReceitaClient api;
    private final HubProperties props;
    private final CenarioCbsService cenario;
    private final CatalogoService catalogo;

    public AuditoriaService(ApiReceitaClient api, HubProperties props, CenarioCbsService cenario, CatalogoService catalogo) {
        this.api = api;
        this.props = props;
        this.cenario = cenario;
        this.catalogo = catalogo;
    }

    /** Audita todos os documentos importados (em paralelo, limitado a {@code hub.paralelismo}). */
    public ResultadoAuditoria auditar(Importacao imp, Andamento andamento) {
        ResultadoAuditoria r = new ResultadoAuditoria();
        r.geradoEm = Valores.dataHoraGeracao();
        r.arquivos.putAll(imp.arquivos);
        r.avisos.addAll(imp.avisos);
        if (!imp.speds.isEmpty()) {
            r.avisos.add("Arquivos SPED não são usados na auditoria (ela confere o grupo IBS/CBS dos XMLs).");
        }
        r.totalDocumentos = imp.documentos.size();
        // só os XMLs (o SPED não entra na auditoria); descrições de NCM pela base vigente hoje
        Importacao soXml = new Importacao();
        soXml.documentos.addAll(imp.documentos);
        r.vendasPorNcm = VendasPorNcm.calcular(soXml, catalogo.descricoesNomenclatura(LocalDate.now()));

        List<List<LinhaAuditoria>> porDocumento;
        try {
            andamento.etapa("Auditando os documentos na calculadora oficial", imp.documentos.size());
            porDocumento = Paralelo.mapear(imp.documentos, props.paralelismo(), this::auditarDocumento, andamento);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Falha na auditoria: " + e.getMessage(), e);
        }
        porDocumento.forEach(r.linhas::addAll);
        r.linhas.sort(Comparator.comparing((LinhaAuditoria l) -> l.status)
                .thenComparing(l -> l.emissao == null ? "" : l.emissao)
                .thenComparing(l -> l.numero == null ? "" : l.numero)
                .thenComparing(l -> l.nItem == null ? 0 : l.nItem));
        r.totalItens = r.linhas.size();
        r.contagemStatus.putAll(StatusAuditoria.contagemZerada());
        BigDecimal xCbs = BigDecimal.ZERO, cCbs = BigDecimal.ZERO, xIbs = BigDecimal.ZERO, cIbs = BigDecimal.ZERO;
        BigDecimal eCbs = BigDecimal.ZERO;
        for (LinhaAuditoria l : r.linhas) {
            l.statusRotulo = l.status.rotulo();
            r.contagemStatus.merge(l.status.name(), 1, Integer::sum);
            if (l.status == StatusAuditoria.OK || l.status == StatusAuditoria.DIVERGENTE) {
                xCbs = xCbs.add(zeroSeNulo(l.xmlVCBS));
                cCbs = cCbs.add(zeroSeNulo(l.calcVCBS));
                eCbs = eCbs.add(zeroSeNulo(l.cenVCBS != null ? l.cenVCBS : l.calcVCBS));
                xIbs = xIbs.add(zeroSeNulo(l.xmlVIBSUF)).add(zeroSeNulo(l.xmlVIBSMun));
                cIbs = cIbs.add(zeroSeNulo(l.calcVIBSUF)).add(zeroSeNulo(l.calcVIBSMun));
            }
        }
        r.totais.put("xmlVCBS", xCbs);
        r.totais.put("calcVCBS", cCbs);
        r.totais.put("xmlVIBS", xIbs);
        r.totais.put("calcVIBS", cIbs);
        if (cenario.disponivel()) {
            r.totais.put("cenVCBS", eCbs);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rotulo", cenario.configuracao().rotulo());
            c.put("aliquota", cenario.configuracao().aliquota());
            r.cenarioCbs = c;
        }
        return r;
    }

    /**
     * Mesma resposta oficial (cálculo (1)), com a CBS reescalada localmente para a alíquota do cenário
     * ({@link CenarioCbsService}, o mesmo cálculo do simulador). Não faz nova chamada de cálculo à calculadora:
     * o contrato com a API da Receita não muda.
     */
    private void aplicarCenario(DocumentoFiscal d, byte[] respostaOficial, Map<Integer, LinhaAuditoria> porItem) {
        if (!cenario.disponivel()) {
            return;
        }
        try {
            JsonNode cen = cenario.regimeGeral(respostaOficial, LocalDate.parse(d.dataHoraEmissao.substring(0, 10)));
            for (JsonNode obj : cen.path("objetos")) {
                LinhaAuditoria l = porItem.get(obj.path("nObj").asInt(-1));
                JsonNode gCbs = obj.path("tribCalc").path("IBSCBS").path("gIBSCBS").path("gCBS");
                if (l != null && l.status == StatusAuditoria.OK && !gCbs.isMissingNode()) {
                    l.cenPCBS = decimal(gCbs.path("pCBS").asString(null));
                    l.cenVCBS = decimal(gCbs.path("vCBS").asString(null));
                }
            }
        } catch (RuntimeException e) {
            porItem.values().forEach(l -> l.mensagens.add("CBS do cenário não calculada: " + e.getMessage()));
        }
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
                l.status = StatusAuditoria.NAO_AUDITADO;
                l.mensagens.add(d.situacao);
            } else if (!it.temGrupoIbsCbs()) {
                l.status = StatusAuditoria.SEM_GRUPO;
                l.mensagens.add(simples
                        ? "Emitente do Simples Nacional (CRT " + d.emitenteCrt + "): grupo IBS/CBS ausente."
                        : "Item sem o grupo IBSCBS no XML.");
            } else if (simples) {
                l.status = StatusAuditoria.NAO_AUDITADO;
                l.mensagens.add("Emitente do Simples Nacional (CRT " + d.emitenteCrt
                        + "): confira pela Calculadora do Simples Nacional.");
            } else if (d.municipioFatoGerador == null || d.dataHoraEmissao == null) {
                l.status = StatusAuditoria.NAO_AUDITADO;
                l.mensagens.add("Documento sem município do fato gerador ou data de emissão.");
            } else if (!dataValida(d.dataHoraEmissao)) {
                // antes: StringIndexOutOfBounds/NumberFormat derrubava a auditoria do lote inteiro
                l.status = StatusAuditoria.NAO_AUDITADO;
                l.mensagens.add("Data de emissão inválida no XML (" + d.dataHoraEmissao + ").");
            } else if (it.numeroItem == null || porItem.containsKey(it.numeroItem)) {
                // antes: o item ficava sem status e a ordenação do resultado falhava (NullPointerException)
                l.status = StatusAuditoria.NAO_AUDITADO;
                l.mensagens.add("Item sem número (nItem) válido e único no XML.");
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
        ApiResposta r1 = api.postJson(Api.RG, EndpointsReceita.RG_CALCULO_OBSERVABILIDADE, operacao(d, auditaveis, ano, true));
        if (!r1.ok()) {
            String msg = api.mensagemErro(r1);
            for (LinhaAuditoria l : porItem.values()) {
                l.status = StatusAuditoria.ERRO_CLASSIFICACAO;
                l.mensagens.add("Calculadora rejeitou o documento: " + msg);
            }
            return linhas;
        }
        for (Object obj : J.list(api.jsonObjeto(r1), "objetos")) {
            LinhaAuditoria l = porItem.get(J.inteiro(obj, "nObj"));
            if (l != null) {
                registrarCalculo(l, obj);
            }
        }
        aplicarCenario(d, r1.corpo(), porItem);

        // (2) base de cálculo a partir dos componentes (somente NF-e/NFC-e)
        boolean temComponentes = auditaveis.stream().anyMatch(i -> !i.componentes.isEmpty());
        if (d.modelo != null && (d.modelo == 55 || d.modelo == 65) && temComponentes) {
            ApiResposta r2 = api.postJson(Api.RG, EndpointsReceita.RG_CALCULO_OBSERVABILIDADE, operacao(d, auditaveis, ano, false));
            if (r2.ok()) {
                for (Object obj : J.list(api.jsonObjeto(r2), "objetos")) {
                    LinhaAuditoria l = porItem.get(J.inteiro(obj, "nObj"));
                    if (l != null) {
                        l.calcVBCComponentes = J.num(obj, "tribCalc", "IBSCBS", "gIBSCBS", "vBC");
                    }
                }
            }
        }

        // comparação
        for (LinhaAuditoria l : porItem.values()) {
            if (l.status == null) { // a calculadora não devolveu este item
                l.status = StatusAuditoria.ERRO_CLASSIFICACAO;
                l.mensagens.add("A calculadora não devolveu resultado para o item.");
            }
            if (l.status != StatusAuditoria.OK) {
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
                l.status = StatusAuditoria.DIVERGENTE;
            }
        }
        return linhas;
    }

    /** Lê o resultado de um item ({@code objetos[]} da resposta da calculadora) para a linha. */
    private static void registrarCalculo(LinhaAuditoria l, Object obj) {
        String estado = J.str(obj, "estadoItem");
        if (!"CALCULADO".equals(estado) && !"CALCULO_SIMULADO".equals(estado)) {
            l.status = StatusAuditoria.ERRO_CLASSIFICACAO;
            for (Object e : J.list(obj, "erros")) {
                l.mensagens.add(J.str(e, "code") + " – " + J.str(e, "detail"));
            }
            if (l.mensagens.isEmpty()) {
                l.mensagens.add("Item não calculado (" + estado + ").");
            }
            return;
        }
        Object g = J.get(obj, "tribCalc", "IBSCBS", "gIBSCBS");
        l.calcPCBS = J.num(g, "gCBS", "pCBS");
        l.calcVCBS = J.num(g, "gCBS", "vCBS");
        l.calcPIBSUF = J.num(g, "gIBSUF", "pIBSUF");
        l.calcVIBSUF = J.num(g, "gIBSUF", "vIBSUF");
        l.calcPIBSMun = J.num(g, "gIBSMun", "pIBSMun");
        l.calcVIBSMun = J.num(g, "gIBSMun", "vIBSMun");
        l.memoriaCalculo = J.str(obj, "tribCalc", "IBSCBS", "memoriaCalculo", "cbs");
        l.status = StatusAuditoria.OK;
        if ("CALCULO_SIMULADO".equals(estado)) {
            l.mensagens.add("Cálculo simulado com as alíquotas do próprio XML (a partir de 2027 não há "
                    + "alíquota de referência na base oficial).");
        }
    }

    /** Data/hora ISO com data válida nos 10 primeiros caracteres (o ano define a regra de alíquotas). */
    private static boolean dataValida(String dataHora) {
        try {
            LocalDate.parse(dataHora.substring(0, 10));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void comparar(LinhaAuditoria l, String campo, BigDecimal xml, BigDecimal calc) {
        if (calc == null) {
            return;
        }
        BigDecimal x = zeroSeNulo(xml);
        if (x.subtract(calc).abs().compareTo(TOLERANCIA) > 0) {
            l.diferencas.add(campo + ": XML " + x.toPlainString() + " × calculado " + calc.toPlainString());
        }
    }

    /**
     * Corpo do POST para a calculadora (contrato da operação do Regime Geral).
     *
     * @param baseDoXml true: informa a base do XML ({@code baseCalculo}); false: envia os componentes
     *                  ({@code gComponentesBC}) para a calculadora refazer a base
     */
    private static Map<String, Object> operacao(DocumentoFiscal d, List<ItemFiscal> itens, int ano, boolean baseDoXml) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("id", d.chave == null ? "doc-" + d.numero : d.chave);
        op.put("versao", "1.0.0");
        op.put("dhFatoGerador", d.dataHoraEmissao);
        op.put("municipio", d.municipioFatoGerador);
        op.put("uf", Ufs.doMunicipio(d.municipioFatoGerador, d.emitenteUf));
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
            BigDecimal vbc = decimal(it.ibscbs.get("vBC"));
            if (baseDoXml || it.componentes.isEmpty()) {
                m.put("baseCalculo", vbc != null ? vbc : it.valor);
            } else {
                m.put("gComponentesBC", it.componentes);
            }
            if (ano >= ANO_SEM_ALIQUOTA_REFERENCIA) {
                Map<String, Object> aliq = new LinkedHashMap<>();
                aliq.put("cbs", zeroSeNulo(decimal(it.ibscbs.get("pCBS"))));
                aliq.put("ibsEstadual", zeroSeNulo(decimal(it.ibscbs.get("pIBSUF"))));
                aliq.put("ibsMunicipal", zeroSeNulo(decimal(it.ibscbs.get("pIBSMun"))));
                m.put("aliquotasNominais", aliq);
            }
            lista.add(m);
        }
        op.put("itens", lista);
        return op;
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
        l.xmlVBC = decimal(it.ibscbs.get("vBC"));
        l.xmlPCBS = decimal(it.ibscbs.get("pCBS"));
        l.xmlVCBS = decimal(it.ibscbs.get("vCBS"));
        l.xmlPIBSUF = decimal(it.ibscbs.get("pIBSUF"));
        l.xmlVIBSUF = decimal(it.ibscbs.get("vIBSUF"));
        l.xmlPIBSMun = decimal(it.ibscbs.get("pIBSMun"));
        l.xmlVIBSMun = decimal(it.ibscbs.get("vIBSMun"));
        return l;
    }
}
