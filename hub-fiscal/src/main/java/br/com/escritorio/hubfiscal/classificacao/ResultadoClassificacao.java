package br.com.escritorio.hubfiscal.classificacao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resultado completo de um processamento de classificação em lote. */
public class ResultadoClassificacao {

    public String id;
    public String dataReferencia;
    public String geradoEm;
    public List<String> contribuintes = new ArrayList<>();
    public Map<String, String> arquivos = new LinkedHashMap<>();
    public List<String> avisos = new ArrayList<>();
    public int totalItens;
    public Map<String, Integer> contagemStatus = new LinkedHashMap<>();
    public List<LinhaClassificacao> linhas = new ArrayList<>();
    /** Resumo da conferência na calculadora oficial (quantas combinações aceitas, rejeitadas, não conferidas). */
    public String conferenciaOficial;
    /** Vendas por NCM/NBS e empresa (aba "Vendas por NCM" da tela e da planilha). */
    public br.com.escritorio.hubfiscal.importacao.VendasPorNcm.Resumo vendasPorNcm;
}
