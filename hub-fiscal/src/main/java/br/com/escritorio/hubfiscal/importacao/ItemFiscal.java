package br.com.escritorio.hubfiscal.importacao;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Item extraído de um arquivo fiscal (SPED ou XML). Campos públicos para
 * simplificar a montagem pelos leitores; os campos não aplicáveis ficam nulos.
 */
public class ItemFiscal {

    // ---- origem
    public String arquivo;
    /** "EFD ICMS/IPI", "EFD Contribuições", "NF-e", "NFC-e", "NFS-e". */
    public String origem;
    /** Registro de origem no SPED (0200, C170, A170...) ou "det" no XML. */
    public String registro;
    /** Modelo do documento: 55, 65, 91... (null para cadastro do SPED). */
    public Integer modelo;

    // ---- documento
    public String chave;
    public String numeroDocumento;
    public String dataEmissao;
    /** 0 = entrada, 1 = saída (do ponto de vista de quem escritura/emite). */
    public Integer tipoOperacao;
    public String donoCnpj;
    public String donoNome;
    public String emitenteCnpj;
    public String emitenteNome;
    public String emitenteUf;
    public String emitenteCrt;
    public String destinatarioCnpj;
    public String destinatarioNome;

    // ---- item
    public Integer numeroItem;
    public String codigo;
    public String descricao;
    public String ncm;
    public String nbs;
    public String codigoServicoLc116;
    public String tipoItemSped;
    public String cfop;
    public String unidade;
    public BigDecimal quantidade;
    public BigDecimal valor;

    /** Componentes de base (nomes do grupo gComponentesBC da calculadora). */
    public Map<String, BigDecimal> componentes = new LinkedHashMap<>();

    /** Grupo IBSCBS informado no documento (CST, cClassTrib, vBC, pCBS, vCBS...). */
    public Map<String, String> ibscbs = new LinkedHashMap<>();

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
