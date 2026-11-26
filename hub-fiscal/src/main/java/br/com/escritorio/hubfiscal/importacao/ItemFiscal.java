package br.com.escritorio.hubfiscal.importacao;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Item extraído de um arquivo fiscal (SPED ou XML), com os dados usados pela
 * classificação e pela auditoria. Campos públicos para simplificar a montagem
 * pelos leitores; os campos não aplicáveis ficam nulos. Os dados do documento
 * (chave, emitente, destinatário...) ficam em {@link DocumentoFiscal}.
 */
public class ItemFiscal {

    // ---- origem
    /** "EFD ICMS/IPI", "EFD Contribuições", "NF-e", "NFC-e", "NFS-e". */
    public String origem;
    /** Registro de origem no SPED (0200, C170, A170...) ou "det"/"serv" no XML. */
    public String registro;
    /** Modelo do documento: 55, 65, 91... (null para cadastro do SPED). */
    public Integer modelo;
    /** Contribuinte dono do cadastro do item (quem escritura o SPED ou emite a nota). */
    public String donoCnpj;
    public String donoNome;

    // ---- item
    public Integer numeroItem;
    public String codigo;
    public String descricao;
    public String ncm;
    public String nbs;
    public String tipoItemSped;
    public String cfop;
    public String unidade;
    public BigDecimal quantidade;
    public BigDecimal valor;

    /** Componentes de base (nomes do grupo gComponentesBC da calculadora). Imutável e vazio nos itens do SPED. */
    public Map<String, BigDecimal> componentes;

    /** Grupo IBSCBS informado no documento (CST, cClassTrib, vBC, pCBS, vCBS...). Imutável e vazio nos itens do SPED. */
    public Map<String, String> ibscbs;

    /** Item de XML: componentes e grupo IBSCBS preenchidos pelo leitor. */
    public ItemFiscal() {
        this(true);
    }

    private ItemFiscal(boolean comGrupos) {
        this.componentes = comGrupos ? new LinkedHashMap<>() : Map.of();
        this.ibscbs = comGrupos ? new LinkedHashMap<>() : Map.of();
    }

    /**
     * Item sem componentes de base nem grupo IBS/CBS (linhas do SPED): usa mapas vazios compartilhados e imutáveis em
     * vez de dois mapas novos por item (SPED com milhões de linhas: centenas de MB a menos).
     */
    public static ItemFiscal semGrupos() {
        return new ItemFiscal(false);
    }

    public boolean temGrupoIbsCbs() {
        return ibscbs.containsKey("cClassTrib") || ibscbs.containsKey("CST");
    }

    public String cstAtual() {
        return ibscbs.get("CST");
    }

    public String cClassTribAtual() {
        return ibscbs.get("cClassTrib");
    }
}
