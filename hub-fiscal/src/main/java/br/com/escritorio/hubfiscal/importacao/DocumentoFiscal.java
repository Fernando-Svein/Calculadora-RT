package br.com.escritorio.hubfiscal.importacao;

import java.util.ArrayList;
import java.util.List;

/** Documento fiscal eletrônico lido de XML (NF-e, NFC-e ou NFS-e). */
public class DocumentoFiscal {

    public String arquivo;
    public String origem;
    public Integer modelo;
    public String chave;
    public String numero;
    /** Data/hora de emissão no formato ISO com fuso (ex.: 2026-03-10T10:00:00-03:00). */
    public String dataHoraEmissao;
    /** Município do fato gerador (cMunFG na NF-e; cLocIncid na NFS-e). */
    public Integer municipioFatoGerador;
    public String emitenteCnpj;
    public String emitenteNome;
    public String emitenteUf;
    public String emitenteCrt;
    /** Nome fantasia do emitente (xFant), quando informado. */
    public String emitenteFantasia;
    /** Código IBGE do município do emitente (enderEmit/cMun na NF-e; enderNac/cMun na NFS-e). */
    public Integer emitenteMunicipio;
    /** NFS-e: situação do prestador no Simples (opSimpNac: 1 não optante, 2 MEI, 3 ME/EPP). */
    public String emitenteOpSimpNac;
    public String destinatarioCnpj;
    public String destinatarioNome;
    /** Situação especial detectada (protocolo com cStat diferente de autorizado). */
    public String situacao;

    public List<ItemFiscal> itens = new ArrayList<>();
}
