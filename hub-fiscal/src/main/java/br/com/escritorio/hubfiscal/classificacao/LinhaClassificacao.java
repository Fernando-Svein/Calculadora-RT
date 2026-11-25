package br.com.escritorio.hubfiscal.classificacao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import br.com.escritorio.hubfiscal.catalogo.Candidata;

/** Uma linha do resultado da classificação: um produto/serviço do cadastro. */
public class LinhaClassificacao {

    public StatusClassificacao status;
    public String statusRotulo;

    public String donoCnpj;
    public String donoNome;
    public String codigo;
    public String descricao;
    /** "NCM" ou "NBS". */
    public String tipoNomenclatura;
    public String nomenclatura;
    public String descricaoNomenclatura;
    public String tipoItemSped;
    public String origens;
    public Integer modeloAnalisado;
    public String cfopsSaida;
    public String cfopsEntrada;
    public int ocorrencias;
    public BigDecimal valorTotal = BigDecimal.ZERO;
    public boolean impostoSeletivo;

    public String cstAtual;
    public String cClassTribAtual;

    public String cstSugerido;
    public String cClassTribSugerido;
    public String descricaoSugerido;
    public String tratamentoSugerido;
    public String anexoSugerido;
    public String fundamentacaoSugerido;

    /** Classificações vinculadas ao produto/serviço (anexo), em ordem de prioridade. */
    public List<Candidata> alternativas = new ArrayList<>();
    /** Outras possibilidades que dependem da atividade/operação (resumo). */
    public List<String> outrasPossibilidades = new ArrayList<>();
    /** Sugestões derivadas dos CFOPs (exportação, bonificação, transferência...). */
    public List<String> sugestoesPorCfop = new ArrayList<>();
    public List<String> observacoes = new ArrayList<>();
}
