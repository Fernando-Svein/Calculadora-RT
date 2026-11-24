package br.com.escritorio.hubfiscal.importacao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Documento fiscal eletrônico lido de XML (NF-e, NFC-e ou NFS-e). */
public class DocumentoFiscal {

    public String arquivo;
    public String origem;
    public Integer modelo;
    public String chave;
    public String numero;
    public String serie;
    /** Data/hora de emissão no formato ISO com fuso (ex.: 2026-03-10T10:00:00-03:00). */
    public String dataHoraEmissao;
    public Integer tipoOperacao;
    /** Município do fato gerador (cMunFG na NF-e; cLocIncid na NFS-e). */
    public Integer municipioFatoGerador;
    public String emitenteCnpj;
    public String emitenteNome;
    public String emitenteUf;
    public String emitenteCrt;
    public String destinatarioCnpj;
    public String destinatarioNome;
    /** Situação especial detectada (cancelada, evento etc.). */
    public String situacao;

    public List<ItemFiscal> itens = new ArrayList<>();

    /** Totais do grupo IBSCBSTot informados no documento. */
    public Map<String, String> totaisIbsCbs = new LinkedHashMap<>();
}
