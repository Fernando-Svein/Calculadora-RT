package br.com.escritorio.hubfiscal.importacao;

import static br.com.escritorio.hubfiscal.infra.Valores.decimal;
import static br.com.escritorio.hubfiscal.infra.Valores.inteiro;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Leitor de XML de documentos fiscais eletrônicos:
 * <ul>
 * <li>NF-e (modelo 55) e NFC-e (65): {@code nfeProc} ou {@code NFe}</li>
 * <li>NFS-e padrão nacional (91): {@code NFSe}/{@code infNFSe}/{@code DPS}</li>
 * </ul>
 * A leitura é por nome local das tags (independe de namespace) e o parser
 * bloqueia DTD/entidades externas (proteção contra XXE).
 */
public final class XmlFiscalParser {

    /** Componentes de base de cálculo lidos do item da NF-e, com o nome usado pela calculadora. */
    private static final Map<String, String> COMPONENTES_PROD = Map.of(
            "vProd", "vProd", "vFrete", "vFrete", "vSeg", "vSeg", "vOutro", "vOutro", "vDesc", "vDesc");

    private XmlFiscalParser() {
    }

    public static boolean pareceXml(byte[] conteudo) {
        for (int i = 0; i < Math.min(conteudo.length, 64); i++) {
            byte b = conteudo[i];
            if (b == '<') {
                return true;
            }
            if (b != ' ' && b != '\n' && b != '\r' && b != '\t' && (b & 0xFF) != 0xEF && (b & 0xFF) != 0xBB
                    && (b & 0xFF) != 0xBF) {
                return false;
            }
        }
        return false;
    }

    /**
     * Um leitor por thread, reaproveitado entre arquivos (o DocumentBuilder não é thread-safe; reset() limpa o estado).
     * Criar a fábrica a cada XML custava a busca da implementação e a configuração em cada um dos milhares de arquivos
     * de um ZIP.
     */
    private static final ThreadLocal<DocumentBuilder> LEITOR = ThreadLocal.withInitial(() -> {
        try {
            return novoBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("Leitor de XML indisponível: " + e.getMessage(), e);
        }
    });

    private static DocumentBuilder builder() {
        DocumentBuilder b = LEITOR.get();
        b.reset();
        return b;
    }

    private static DocumentBuilder novoBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    /**
     * Lê um XML e devolve o documento fiscal, ou null se o tipo não for
     * suportado (o motivo vai em {@code motivo[0]}).
     */
    public static DocumentoFiscal ler(String arquivo, byte[] conteudo, String[] motivo) {
        Document doc;
        try {
            doc = builder().parse(new ByteArrayInputStream(conteudo));
        } catch (Exception e) {
            motivo[0] = "XML inválido ou corrompido (" + e.getMessage() + ")";
            return null;
        }
        Element raiz = doc.getDocumentElement();
        Element infNFe = primeiro(raiz, "infNFe");
        if (infNFe != null) {
            return lerNFe(arquivo, raiz, infNFe);
        }
        Element infNFSe = primeiro(raiz, "infNFSe");
        if (infNFSe != null) {
            return lerNFSe(arquivo, infNFSe);
        }
        String nome = raiz.getLocalName();
        if (nome != null && (nome.contains("Evento") || nome.contains("evento"))) {
            motivo[0] = "Evento (" + nome + ") – ignorado";
        } else if (primeiro(raiz, "infCte") != null) {
            motivo[0] = "CT-e ainda não suportado na importação";
        } else {
            motivo[0] = "Tipo de XML não reconhecido (raiz: " + nome + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ NF-e / NFC-e

    private static DocumentoFiscal lerNFe(String arquivo, Element raiz, Element inf) {
        DocumentoFiscal d = new DocumentoFiscal();
        d.arquivo = arquivo;
        Element ide = filho(inf, "ide");
        Element emit = filho(inf, "emit");
        Element dest = filho(inf, "dest");
        d.modelo = inteiro(texto(ide, "mod"));
        d.origem = d.modelo != null && d.modelo == 65 ? "NFC-e" : "NF-e";
        String id = inf.getAttribute("Id");
        d.chave = id != null && id.startsWith("NFe") ? id.substring(3) : null;
        if (d.chave == null || d.chave.isBlank()) {
            d.chave = texto(raiz, "chNFe");
        }
        d.numero = texto(ide, "nNF");
        String dh = texto(ide, "dhEmi");
        if (dh == null) {
            String dEmi = texto(ide, "dEmi");
            dh = dEmi == null ? null : dEmi + "T00:00:00-03:00";
        }
        d.dataHoraEmissao = dh;
        d.municipioFatoGerador = inteiro(texto(ide, "cMunFG"));
        d.emitenteCnpj = coalesce(texto(emit, "CNPJ"), texto(emit, "CPF"));
        d.emitenteNome = texto(emit, "xNome");
        d.emitenteUf = texto(primeiro(emit, "enderEmit"), "UF");
        d.emitenteCrt = texto(emit, "CRT");
        d.emitenteFantasia = texto(emit, "xFant");
        d.emitenteMunicipio = inteiro(texto(primeiro(emit, "enderEmit"), "cMun"));
        if (dest != null) {
            d.destinatarioCnpj = coalesce(texto(dest, "CNPJ"), coalesce(texto(dest, "CPF"), texto(dest, "idEstrangeiro")));
            d.destinatarioNome = texto(dest, "xNome");
        }
        String cStat = texto(raiz, "cStat");
        if (cStat != null && !cStat.equals("100") && !cStat.equals("150")) {
            d.situacao = "Protocolo com cStat " + cStat + " (" + texto(raiz, "xMotivo") + ")";
        }

        for (Element det : filhos(inf, "det")) {
            ItemFiscal it = novoItem(d);
            it.registro = "det";
            it.numeroItem = inteiro(det.getAttribute("nItem"));
            Element prod = filho(det, "prod");
            it.codigo = texto(prod, "cProd");
            it.descricao = texto(prod, "xProd");
            it.ncm = SpedParser.somenteDigitos(texto(prod, "NCM"));
            it.cfop = texto(prod, "CFOP");
            it.unidade = texto(prod, "uCom");
            it.quantidade = decimal(texto(prod, "qCom"));
            it.valor = decimal(texto(prod, "vProd"));
            it.desconto = decimal(texto(prod, "vDesc"));
            it.chave = d.chave;
            for (Map.Entry<String, String> e : COMPONENTES_PROD.entrySet()) {
                BigDecimal v = decimal(texto(prod, e.getKey()));
                if (v != null && v.signum() != 0) {
                    it.componentes.put(e.getValue(), v);
                }
            }
            Element imposto = filho(det, "imposto");
            if (imposto != null) {
                lerComponentesImposto(imposto, it);
                Element ibscbs = filho(imposto, "IBSCBS");
                if (ibscbs != null) {
                    lerGrupoIbsCbs(ibscbs, it.ibscbs);
                }
            }
            d.itens.add(it);
        }
        return d;
    }

    private static void lerComponentesImposto(Element imposto, ItemFiscal it) {
        Element icms = filho(imposto, "ICMS");
        Element grupoIcms = icms == null ? null : primeiroFilhoElemento(icms);
        if (grupoIcms != null) {
            soma(it, "vICMS", texto(grupoIcms, "vICMS"));
            soma(it, "vFCP", texto(grupoIcms, "vFCP"));
            soma(it, "vICMSMono", texto(grupoIcms, "vICMSMono"));
        }
        Element difal = filho(imposto, "ICMSUFDest");
        if (difal != null) {
            soma(it, "vICMSUFDest", texto(difal, "vICMSUFDest"));
            soma(it, "vFCPUFDest", texto(difal, "vFCPUFDest"));
        }
        Element pis = filho(imposto, "PIS");
        if (pis != null) {
            soma(it, "vPIS", texto(primeiroFilhoElemento(pis), "vPIS"));
        }
        Element cofins = filho(imposto, "COFINS");
        if (cofins != null) {
            soma(it, "vCOFINS", texto(primeiroFilhoElemento(cofins), "vCOFINS"));
        }
        Element ii = filho(imposto, "II");
        if (ii != null) {
            soma(it, "vII", texto(ii, "vII"));
        }
        Element issqn = filho(imposto, "ISSQN");
        if (issqn != null) {
            soma(it, "vISSQN", texto(issqn, "vISSQN"));
        }
        Element is = filho(imposto, "IS");
        if (is != null) {
            soma(it, "vIS", texto(is, "vIS"));
        }
    }

    /** Lê CST, cClassTrib e os valores do grupo IBSCBS (por nome local, em qualquer nível). */
    private static void lerGrupoIbsCbs(Element g, Map<String, String> destino) {
        for (String tag : List.of("CST", "cClassTrib", "vBC", "pIBSUF", "vIBSUF", "pIBSMun", "vIBSMun", "vIBS", "pCBS",
                "vCBS")) {
            String v = texto(g, tag);
            if (v != null) {
                destino.put(tag, v);
            }
        }
        // percentuais de redução (gRed) por esfera
        String redUf = texto(primeiro(primeiro(g, "gIBSUF"), "gRed"), "pRedAliq");
        String redMun = texto(primeiro(primeiro(g, "gIBSMun"), "gRed"), "pRedAliq");
        String redCbs = texto(primeiro(primeiro(g, "gCBS"), "gRed"), "pRedAliq");
        if (redUf != null) {
            destino.put("pRedAliqIBSUF", redUf);
        }
        if (redMun != null) {
            destino.put("pRedAliqIBSMun", redMun);
        }
        if (redCbs != null) {
            destino.put("pRedAliqCBS", redCbs);
        }
    }

    // ------------------------------------------------------------------ NFS-e nacional

    private static DocumentoFiscal lerNFSe(String arquivo, Element inf) {
        DocumentoFiscal d = new DocumentoFiscal();
        d.arquivo = arquivo;
        d.modelo = 91;
        d.origem = "NFS-e";
        String id = inf.getAttribute("Id");
        d.chave = id != null && id.startsWith("NFS") ? id.substring(3) : id;
        d.numero = texto(inf, "nNFSe");
        Element emit = filho(inf, "emit");
        Element infDps = primeiro(inf, "infDPS");
        Element prest = primeiro(infDps, "prest");
        Element toma = primeiro(infDps, "toma");
        d.emitenteCnpj = coalesce(texto(emit, "CNPJ"), coalesce(texto(prest, "CNPJ"), texto(prest, "CPF")));
        d.emitenteNome = coalesce(texto(emit, "xNome"), texto(prest, "xNome"));
        d.emitenteUf = texto(primeiro(emit, "enderNac"), "UF");
        String opSimp = texto(prest, "opSimpNac");
        d.emitenteCrt = opSimp == null ? null : "opSimpNac=" + opSimp;
        d.emitenteOpSimpNac = opSimp;
        d.emitenteMunicipio = inteiro(texto(primeiro(emit, "enderNac"), "cMun"));
        if (toma != null) {
            d.destinatarioCnpj = coalesce(texto(toma, "CNPJ"), texto(toma, "CPF"));
            d.destinatarioNome = texto(toma, "xNome");
        }
        d.dataHoraEmissao = coalesce(texto(infDps, "dhEmi"), texto(inf, "dhProc"));
        d.municipioFatoGerador = inteiro(coalesce(texto(inf, "cLocIncid"), texto(primeiro(infDps, "locPrest"), "cLocPrestacao")));

        ItemFiscal it = novoItem(d);
        it.registro = "serv";
        it.numeroItem = 1;
        Element serv = primeiro(infDps, "serv");
        Element cServ = primeiro(serv, "cServ");
        it.codigo = coalesce(texto(cServ, "cTribNac"), texto(cServ, "cTribMun"));
        it.descricao = texto(cServ, "xDescServ");
        it.nbs = SpedParser.somenteDigitos(texto(cServ, "cNBS"));
        Element valores = primeiro(infDps, "valores");
        it.valor = decimal(coalesce(texto(primeiro(valores, "vServPrest"), "vServ"), texto(valores, "vServ")));
        it.desconto = decimal(texto(primeiro(valores, "vDescCondIncond"), "vDescIncond"));
        it.chave = d.chave;
        it.saida = true; // NFS-e: prestação de serviço pelo emitente
        // Classificação informada na DPS e valores calculados na NFS-e
        Element ibscbsDps = primeiro(infDps, "IBSCBS");
        if (ibscbsDps != null) {
            lerGrupoIbsCbs(ibscbsDps, it.ibscbs);
        }
        Element ibscbsNfse = filho(inf, "IBSCBS");
        if (ibscbsNfse != null) {
            lerGrupoIbsCbs(ibscbsNfse, it.ibscbs);
        }
        d.itens.add(it);
        return d;
    }

    // ------------------------------------------------------------------ utilitários

    private static ItemFiscal novoItem(DocumentoFiscal d) {
        ItemFiscal it = new ItemFiscal();
        it.origem = d.origem;
        it.modelo = d.modelo;
        // o "dono" do cadastro é o emitente (o produto é dele)
        it.donoCnpj = d.emitenteCnpj;
        it.donoNome = d.emitenteNome;
        return it;
    }

    private static void soma(ItemFiscal it, String chave, String valor) {
        BigDecimal v = decimal(valor);
        if (v != null && v.signum() != 0) {
            it.componentes.merge(chave, v, BigDecimal::add);
        }
    }

    /** Filho direto com o nome local informado. */
    static Element filho(Element pai, String nome) {
        if (pai == null) {
            return null;
        }
        for (Node n = pai.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && nome.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    static List<Element> filhos(Element pai, String nome) {
        List<Element> r = new ArrayList<>();
        if (pai == null) {
            return r;
        }
        for (Node n = pai.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && nome.equals(e.getLocalName())) {
                r.add(e);
            }
        }
        return r;
    }

    private static Element primeiroFilhoElemento(Element pai) {
        if (pai == null) {
            return null;
        }
        for (Node n = pai.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    /** Primeiro descendente (qualquer nível) com o nome local informado. */
    static Element primeiro(Element pai, String nome) {
        if (pai == null) {
            return null;
        }
        NodeList l = pai.getElementsByTagNameNS("*", nome);
        return l.getLength() > 0 ? (Element) l.item(0) : null;
    }

    /** Texto do primeiro descendente com o nome local informado. */
    static String texto(Element pai, String nome) {
        Element e = primeiro(pai, nome);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }
}
