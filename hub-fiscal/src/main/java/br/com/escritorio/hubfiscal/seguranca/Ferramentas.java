package br.com.escritorio.hubfiscal.seguranca;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.util.UrlPathHelper;

import br.com.escritorio.hubfiscal.infra.Api;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Ferramentas do hub que podem ser liberadas por usuário (os ids são as rotas das telas)
 * e as operações do servidor que cada uma exige.
 *
 * <p>Início, Situação do sistema e Guia rápido são sempre liberados; Usuários é só do administrador.
 * As tabelas de referência (UFs, municípios, NCM/NBS, cClassTrib, atividades...) ficam abertas a
 * todo usuário logado porque várias telas dependem delas; o que é restrito são as operações
 * (cálculos, lotes, validações).
 */
public final class Ferramentas {

    private Ferramentas() {
    }

    public record Ferramenta(String id, String titulo, String grupo) {
    }

    public static final List<Ferramenta> TODAS = List.of(
            new Ferramenta("empresas", "Empresas (incluir, editar e excluir)", "Cadastros"),
            new Ferramenta("classificar-item", "Classificar NCM / NBS", "Consultas e tabelas"),
            new Ferramenta("tabela-cclasstrib", "Tabela cClassTrib e CST", "Consultas e tabelas"),
            new Ferramenta("aliquotas", "Alíquotas de referência", "Consultas e tabelas"),
            new Ferramenta("atividades-simples", "Atividades do Simples", "Consultas e tabelas"),
            new Ferramenta("nfse", "Ferramentas NFS-e", "Consultas e tabelas"),
            new Ferramenta("dados-abertos", "Outras tabelas oficiais", "Consultas e tabelas"),
            new Ferramenta("erros", "Códigos de erro", "Consultas e tabelas"),
            new Ferramenta("calc-rg", "Simulador Regime Regular", "Calculadoras – Regime Regular"),
            new Ferramenta("bases", "Bases de cálculo", "Calculadoras – Regime Regular"),
            new Ferramenta("pedagio", "Pedágio", "Calculadoras – Regime Regular"),
            new Ferramenta("calc-sn", "Simulador Simples Nacional", "Calculadoras – Simples Nacional"),
            new Ferramenta("comparador", "Comparador Simples × Regular", "Calculadoras – Simples Nacional"),
            new Ferramenta("lote-classificacao", "Classificar cadastro (SPED/XML)", "Processamento em lote"),
            new Ferramenta("lote-auditoria", "Auditar XML (IBS/CBS)", "Processamento em lote"),
            new Ferramenta("xml-validar", "Validar XML no leiaute", "Documentos XML"));

    public static final Set<String> IDS = TODAS.stream().map(Ferramenta::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    /** Operação do servidor → ferramentas que a liberam (basta ter uma). */
    private record Regra(String metodo, String caminho, boolean exato, Set<String> ferramentas) {
        boolean casa(String m, String c) {
            if (metodo != null && !metodo.equals(m)) {
                return false;
            }
            return exato ? c.equals(caminho) : c.startsWith(caminho);
        }
    }

    private static final String RG = "/proxy/rg/calculadora";
    private static final String SN = "/proxy/sn/calculadora";

    /** A primeira regra que casar vale (as mais específicas vêm antes). */
    private static final List<Regra> REGRAS = List.of(
            // empresas: a consulta (GET) é aberta porque os simuladores usam o seletor; alterar exige a ferramenta
            new Regra("GET", "/hub/empresas/consulta-cnpj", false, Set.of("empresas")), // chama serviço externo
            new Regra("POST", "/hub/empresas", false, Set.of("empresas")),
            new Regra("PUT", "/hub/empresas", false, Set.of("empresas")),
            new Regra("DELETE", "/hub/empresas", false, Set.of("empresas")),
            new Regra(null, "/hub/simulador/importar", false, Set.of("calc-rg")),
            new Regra(null, "/hub/lote/classificacao", false, Set.of("lote-classificacao")),
            new Regra(null, "/hub/lote/auditoria", false, Set.of("lote-auditoria")),
            new Regra("POST", RG + "/xml/validate", false, Set.of("xml-validar", "calc-rg")),
            new Regra(null, RG + "/xml/generate", false, Set.of("calc-rg")),
            new Regra(null, RG + "/regime-geral", true, Set.of("calc-rg")),
            new Regra(null, RG + "/observabilidade/regime-geral", true, Set.of("calc-rg", "comparador")),
            new Regra(null, SN + "/observabilidade/simples-nacional", true, Set.of("calc-sn", "comparador")),
            new Regra(null, SN + "/observabilidade/simples-nacional/nao-assistido", true, Set.of("calc-sn", "comparador")),
            new Regra(null, RG + "/pedagio", false, Set.of("pedagio")),
            new Regra(null, RG + "/base-calculo/", false, Set.of("bases")),
            new Regra(null, RG + "/nfse/base-calculo", false, Set.of("bases")),
            new Regra(null, RG + "/nfse/", false, Set.of("nfse")));

    /**
     * Ferramentas que liberam a operação, ou vazio se a operação é aberta a todo usuário logado.
     * {@code caminho} deve ser o {@link #caminhoCanonico canônico}; a comparação ignora maiúsculas.
     */
    public static Optional<Set<String>> exigidas(String metodo, String caminho) {
        String c = caminho.toLowerCase(Locale.ROOT);
        String m = metodo == null ? null : metodo.toUpperCase(Locale.ROOT);
        return REGRAS.stream().filter(r -> r.casa(m, c)).findFirst().map(Regra::ferramentas);
    }

    private static final UrlPathHelper CAMINHOS = new UrlPathHelper();

    /**
     * Caminho da requisição na forma usada TANTO na checagem de permissão QUANTO no repasse do proxy:
     * decodificado ({@code %2D} → {@code -}), sem barras finais e com o apelido da calculadora
     * normalizado ({@code /proxy/RG}, {@code /proxy/regime-geral} → {@code /proxy/rg}).
     * Sem isso, variações do mesmo endereço passavam pela checagem sem casar com nenhuma regra
     * e chegavam à calculadora (que as aceita), liberando ferramentas não autorizadas.
     */
    public static String caminhoCanonico(HttpServletRequest req) {
        String c = CAMINHOS.getPathWithinApplication(req); // decodifica e remove ;parâmetros
        while (c.length() > 1 && c.endsWith("/")) {
            c = c.substring(0, c.length() - 1);
        }
        if (c.regionMatches(true, 0, "/proxy/", 0, 7)) {
            String resto = c.substring(7);
            int barra = resto.indexOf('/');
            String apelido = barra < 0 ? resto : resto.substring(0, barra);
            try {
                String api = Api.of(apelido) == Api.RG ? "rg" : "sn";
                c = "/proxy/" + api + (barra < 0 ? "" : resto.substring(barra));
            } catch (IllegalArgumentException e) {
                // API desconhecida: o proxy responde 404
            }
        }
        return c;
    }
}
