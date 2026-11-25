package br.com.escritorio.hubfiscal.auditoria;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Resultado da auditoria de um item de documento fiscal. */
public class LinhaAuditoria {

    public StatusAuditoria status;
    public String statusRotulo;

    public String arquivo;
    public String origem;
    public String chave;
    public String numero;
    public String emissao;
    public String emitenteCnpj;
    public String emitenteNome;
    public String emitenteCrt;
    public String destinatario;

    public Integer nItem;
    public String codigo;
    public String descricao;
    public String ncmNbs;
    public String cfop;
    public String cst;
    public String cClassTrib;

    // informados no XML
    public BigDecimal xmlVBC;
    public BigDecimal xmlPCBS;
    public BigDecimal xmlVCBS;
    public BigDecimal xmlPIBSUF;
    public BigDecimal xmlVIBSUF;
    public BigDecimal xmlPIBSMun;
    public BigDecimal xmlVIBSMun;

    // calculados pela calculadora oficial
    /** Base calculada a partir dos componentes do item (vProd, frete, descontos, ICMS, PIS...). */
    public BigDecimal calcVBCComponentes;
    public BigDecimal calcPCBS;
    public BigDecimal calcVCBS;
    public BigDecimal calcPIBSUF;
    public BigDecimal calcVIBSUF;
    public BigDecimal calcPIBSMun;
    public BigDecimal calcVIBSMun;

    public List<String> diferencas = new ArrayList<>();
    public List<String> mensagens = new ArrayList<>();
    public String memoriaCalculo;
}
