package br.com.escritorio.hubfiscal.empresa;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.empresa.Empresa.Regime;
import br.com.escritorio.hubfiscal.importacao.DocumentoFiscal;
import br.com.escritorio.hubfiscal.importacao.ImportacaoService.Importacao;
import br.com.escritorio.hubfiscal.importacao.SpedParser.ResultadoSped;

/**
 * Cadastro de empresas: validação, empresas encontradas na importação (o usuário escolhe quais adicionar; cada uma é
 * cadastrada com a consulta pública do CNPJ) e manutenção das particularidades.
 */
@Service
public class EmpresaService {

    private static final Logger log = LoggerFactory.getLogger(EmpresaService.class);

    private static final Set<String> UFS = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG",
            "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private static final Set<String> ANEXOS = Set.of("I", "II", "III", "IV", "V");
    private static final Set<String> CAMPOS_ALIQUOTA = Set.of("cbs", "ibs", "cbsSemIss", "ibsSemIss");
    private static final Set<String> DOCUMENTOS = Set.of("55", "65", "91", "57", "67", "63", "93", "62", "66", "75");

    /** Dados enviados pela tela de cadastro (criação e alteração). */
    public record DadosEmpresa(String cnpj, String razaoSocial, String nomeFantasia, String uf, Integer municipio,
            String regime, String regimeApuracao, Boolean ibscbsForaSimples, Boolean fatorR, Boolean sublimiteExcedido,
            String atividadeSimples, Map<String, Map<String, String>> aliquotasSimples, String documentoPadrao,
            String cClassTribPadrao, String observacoes, Map<String, Object> dadosCadastrais) {
    }

    private final EmpresaRepositorio repo;

    public EmpresaService(EmpresaRepositorio repo) {
        this.repo = repo;
    }

    public List<Empresa> listar() {
        return repo.listar();
    }

    public Empresa obter(long id) {
        return repo.porId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }

    public Empresa criar(DadosEmpresa d) {
        Empresa e = validar(d, null);
        if (repo.porCnpj(e.cnpj()).isPresent()) {
            throw new ErroNegocio("Já existe uma empresa cadastrada com o CNPJ " + formatarCnpj(e.cnpj()) + ".");
        }
        return obter(repo.inserir(e));
    }

    public Empresa atualizar(long id, DadosEmpresa d) {
        Empresa atual = obter(id);
        repo.atualizar(id, validar(d, atual.cnpj()));
        return obter(id);
    }

    public void excluir(long id) {
        obter(id);
        repo.excluir(id);
    }

    /** Exclusão de várias empresas (seleção na lista). Devolve quantas foram excluídas. */
    public int excluirVarias(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ErroNegocio("Selecione ao menos uma empresa.");
        }
        if (ids.size() > 5_000) {
            throw new ErroNegocio("Seleção grande demais (máximo de 5 mil empresas por vez).");
        }
        int n = 0;
        for (Long id : new java.util.LinkedHashSet<>(ids)) {
            if (id != null && repo.porId(id).isPresent()) {
                repo.excluir(id);
                n++;
            }
        }
        log.info("{} empresa(s) excluída(s) pela seleção da lista", n);
        return n;
    }

    // ------------------------------------------------------------------ empresas encontradas na importação

    /** Empresa de um arquivo importado que ainda não está no cadastro (dados lidos do próprio arquivo). */
    public record EmpresaEncontrada(String cnpj, String razaoSocial, String nomeFantasia, String uf, Integer municipio,
            String regime, String regimeApuracao, String origem) {
    }

    /** Resultado do cadastro de uma empresa encontrada: consulta = "OK" ou o motivo de não ter consultado a API. */
    public record ResultadoCadastro(String cnpj, String razaoSocial, Long id, String situacao, String consulta) {
    }

    private record Pendentes(long usuario, List<EmpresaEncontrada> empresas, Instant criadoEm) {
    }

    /** A tela pergunta logo após a importação; depois disso a lista é descartada. */
    private static final Duration VALIDADE_PENDENTES = Duration.ofHours(2);
    private static final int LIMITE_PENDENTES = 500;
    private final Map<String, Pendentes> pendentes = new ConcurrentHashMap<>();

    /**
     * Separa as empresas dos arquivos que ainda não estão no cadastro, sem gravar nada: a tela pergunta ao usuário quais
     * adicionar ({@link #cadastrarEncontradas}). Devolve o código da lista, ou vazio se não houver empresa nova.
     */
    public Optional<String> guardarEncontradas(Importacao imp, long usuario) {
        Instant agora = Instant.now();
        pendentes.values().removeIf(p -> p.criadoEm().plus(VALIDADE_PENDENTES).isBefore(agora));
        List<EmpresaEncontrada> novas = encontradas(imp);
        if (novas.isEmpty()) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        pendentes.put(token, new Pendentes(usuario, novas.size() > LIMITE_PENDENTES ? List.copyOf(novas.subList(0, LIMITE_PENDENTES)) : novas, agora));
        return Optional.of(token);
    }

    /** Empresas encontradas guardadas para o usuário (só ele vê a própria lista). */
    public List<EmpresaEncontrada> encontradasGuardadas(String token, long usuario) {
        Pendentes p = pendentes.get(token);
        if (p == null || p.usuario() != usuario || p.criadoEm().plus(VALIDADE_PENDENTES).isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "A lista de empresas desta importação expirou. Importe os arquivos de novo.");
        }
        return p.empresas();
    }

    /**
     * Cadastra as empresas escolhidas, cada uma com a consulta completa do CNPJ na API pública (situação, CNAEs,
     * endereço, Simples/MEI, regime declarado e quadro societário). Se a consulta falhar, a empresa entra com os dados
     * do arquivo e o motivo volta para a tela; com o limite de consultas atingido, as seguintes não são consultadas.
     */
    public List<ResultadoCadastro> cadastrarEncontradas(String token, List<String> cnpjs, long usuario, ConsultaCnpjService consulta) {
        List<EmpresaEncontrada> lista = encontradasGuardadas(token, usuario);
        if (cnpjs == null || cnpjs.isEmpty()) {
            throw new ErroNegocio("Selecione ao menos uma empresa para adicionar.");
        }
        Set<String> escolhidas = new java.util.HashSet<>();
        cnpjs.forEach(c -> escolhidas.add(normalizarCnpj(c)));
        List<ResultadoCadastro> r = new ArrayList<>();
        String semConsulta = consulta.disponivel() ? null : "consulta de CNPJ desativada neste servidor";
        for (EmpresaEncontrada e : lista) {
            if (!escolhidas.contains(e.cnpj())) {
                continue;
            }
            if (repo.porCnpj(e.cnpj()).isPresent()) {
                r.add(new ResultadoCadastro(e.cnpj(), e.razaoSocial(), repo.porCnpj(e.cnpj()).get().id(), "JA_CADASTRADA", null));
                continue;
            }
            Map<String, Object> pub = null;
            String motivo = semConsulta;
            if (motivo == null) {
                try {
                    pub = consulta.consultar(e.cnpj());
                } catch (ResponseStatusException ex) {
                    motivo = ex.getReason();
                    if (ex.getStatusCode().value() == 429) {
                        semConsulta = "limite de consultas do serviço atingido; consulte depois em Cadastros → Empresas";
                    }
                } catch (RuntimeException ex) {
                    motivo = ex.getMessage();
                }
            }
            Map<?, ?> end = pub == null ? null : (Map<?, ?>) pub.get("endereco");
            Map<?, ?> sug = pub == null ? null : (Map<?, ?>) pub.get("regimeSugerido");
            String razao = pub != null && pub.get("razaoSocial") != null ? String.valueOf(pub.get("razaoSocial")) : e.razaoSocial();
            String fantasia = pub != null && pub.get("nomeFantasia") != null ? String.valueOf(pub.get("nomeFantasia")) : e.nomeFantasia();
            String uf = end != null && end.get("uf") != null ? ufValida(String.valueOf(end.get("uf"))) : e.uf();
            Integer mun = end != null && end.get("codigoMunicipioIbge") instanceof Number n ? n.intValue() : e.municipio();
            // regime: o do arquivo (CRT da nota, EFD Contribuições) vale mais; sem ele, o indicado pela base pública
            String regime = e.regime() != null ? e.regime() : sug == null ? null : (String) sug.get("regime");
            String apuracao = e.regimeApuracao() != null ? e.regimeApuracao()
                    : sug != null && "NORMAL".equals(regime) ? (String) sug.get("regimeApuracao") : null;
            repo.inserirSeNovo(e.cnpj(), limitar(razao, 150), limitar(fantasia, 150), uf, mun, regime, apuracao, e.origem(), pub);
            Long id = repo.porCnpj(e.cnpj()).map(Empresa::id).orElse(null);
            r.add(new ResultadoCadastro(e.cnpj(), razao, id, "CADASTRADA", pub != null ? "OK" : motivo));
        }
        log.info("Importação: {} empresa(s) adicionada(s) ao cadastro pelo usuário", r.stream().filter(x -> "CADASTRADA".equals(x.situacao())).count());
        return r;
    }

    /**
     * Empresas dos arquivos que não estão no cadastro: o contribuinte do SPED (registro 0000) e o emitente de cada XML.
     * CPF, CNPJ inválido e CNPJ já cadastrado ficam de fora.
     */
    List<EmpresaEncontrada> encontradas(Importacao imp) {
        Map<String, String[]> candidatas = new LinkedHashMap<>(); // cnpj → razão, fantasia, uf, município, regime, apuração, origem
        for (ResultadoSped s : imp.speds) {
            String cnpj = normalizarCnpj(s.cnpj());
            if (!cnpjValido(cnpj) || s.nome() == null || s.nome().isBlank()) {
                continue;
            }
            // EFD Contribuições: só pessoas jurídicas fora do Simples entregam. 0110: 1 = não cumulativo (Lucro Real),
            // 2 = cumulativo (em regra Lucro Presumido). EFD ICMS/IPI não indica o regime.
            boolean contribuicoes = s.tipo() != null && s.tipo().startsWith("EFD Contribui");
            String regime = contribuicoes ? Regime.NORMAL.name() : null;
            String apuracao = !contribuicoes ? null
                    : "1".equals(s.incidenciaPisCofins()) ? "LUCRO_REAL"
                    : "2".equals(s.incidenciaPisCofins()) ? "LUCRO_PRESUMIDO" : null;
            candidatas.putIfAbsent(cnpj, new String[] { s.nome().trim(), null, s.uf(),
                    s.municipio() == null ? null : String.valueOf(s.municipio()), regime, apuracao, "SPED" });
        }
        for (DocumentoFiscal d : imp.documentos) {
            String cnpj = normalizarCnpj(d.emitenteCnpj);
            if (!cnpjValido(cnpj) || d.emitenteNome == null || d.emitenteNome.isBlank() || candidatas.containsKey(cnpj)) {
                continue;
            }
            candidatas.put(cnpj, new String[] { d.emitenteNome.trim(), d.emitenteFantasia, ufValida(d.emitenteUf),
                    d.emitenteMunicipio == null ? null : String.valueOf(d.emitenteMunicipio), regimeDoDocumento(d), null, "XML" });
        }
        Set<String> cadastrados = new java.util.HashSet<>();
        repo.listar().forEach(e -> cadastrados.add(e.cnpj()));
        List<EmpresaEncontrada> novas = new ArrayList<>();
        for (Map.Entry<String, String[]> c : candidatas.entrySet()) {
            if (cadastrados.contains(c.getKey())) {
                continue;
            }
            String[] v = c.getValue();
            Integer mun = v[3] != null && v[3].matches("\\d{7}") ? Integer.valueOf(v[3]) : null;
            novas.add(new EmpresaEncontrada(c.getKey(), limitar(v[0], 150), limitar(v[1], 150), v[2], mun, v[4], v[5], v[6]));
        }
        return novas;
    }

    /** CRT da NF-e (1 a 4) ou opSimpNac da NFS-e (1 não optante, 2 MEI, 3 ME/EPP). */
    private static String regimeDoDocumento(DocumentoFiscal d) {
        if (d.emitenteOpSimpNac != null) {
            return switch (d.emitenteOpSimpNac) {
                case "1" -> Regime.NORMAL.name();
                case "2" -> Regime.MEI.name();
                case "3" -> Regime.SIMPLES.name();
                default -> null;
            };
        }
        Regime r = Regime.porCrt(d.emitenteCrt);
        return r == null ? null : r.name();
    }

    // ------------------------------------------------------------------ validação

    private Empresa validar(DadosEmpresa d, String cnpjExistente) {
        if (d == null) {
            throw new ErroNegocio("Informe os dados da empresa.");
        }
        String cnpj = cnpjExistente != null ? cnpjExistente : normalizarCnpj(d.cnpj());
        if (!cnpjValido(cnpj)) {
            throw new ErroNegocio("CNPJ inválido. Confira os 14 caracteres e os dígitos verificadores.");
        }
        String razao = texto(d.razaoSocial(), 150);
        if (razao == null) {
            throw new ErroNegocio("Informe a razão social.");
        }
        String uf = d.uf() == null || d.uf().isBlank() ? null : ufValida(d.uf());
        if (d.uf() != null && !d.uf().isBlank() && uf == null) {
            throw new ErroNegocio("UF inválida: " + d.uf() + ".");
        }
        Integer mun = d.municipio();
        if (mun != null && (mun < 1_000_000 || mun > 9_999_999)) {
            throw new ErroNegocio("Código de município inválido (use o código IBGE de 7 dígitos).");
        }
        String regime = d.regime() == null || d.regime().isBlank() ? null : d.regime().trim().toUpperCase(Locale.ROOT);
        if (regime != null) {
            try {
                Regime.valueOf(regime);
            } catch (IllegalArgumentException e) {
                throw new ErroNegocio("Regime tributário inválido: " + d.regime() + ".");
            }
        }
        String apuracao = d.regimeApuracao() == null || d.regimeApuracao().isBlank() ? null
                : d.regimeApuracao().trim().toUpperCase(Locale.ROOT);
        if (apuracao != null && !Empresa.APURACOES.contains(apuracao)) {
            throw new ErroNegocio("Forma de apuração inválida: " + d.regimeApuracao() + ".");
        }
        boolean simples = Regime.SIMPLES.name().equals(regime) || Regime.SIMPLES_EXCESSO.name().equals(regime);
        if (!Regime.NORMAL.name().equals(regime)) {
            apuracao = null; // Lucro Real/Presumido só se aplica ao regime normal
        }
        String atividade = texto(d.atividadeSimples(), 10);
        if (atividade != null && !atividade.matches("\\d{1,4}")) {
            throw new ErroNegocio("Atividade do Simples inválida: use o código da atividade (ex.: 01).");
        }
        String documento = texto(d.documentoPadrao(), 3);
        if (documento != null && !DOCUMENTOS.contains(documento)) {
            throw new ErroNegocio("Documento fiscal padrão inválido: " + documento + ".");
        }
        String cct = texto(d.cClassTribPadrao(), 6);
        if (cct != null && !cct.matches("\\d{6}")) {
            throw new ErroNegocio("Classificação tributária (cClassTrib) padrão deve ter 6 dígitos.");
        }
        return new Empresa(0, cnpj, razao, texto(d.nomeFantasia(), 150), uf, mun, regime, apuracao,
                simples && Boolean.TRUE.equals(d.ibscbsForaSimples()), simples && Boolean.TRUE.equals(d.fatorR()),
                simples && Boolean.TRUE.equals(d.sublimiteExcedido()), simples ? atividade : null,
                simples ? aliquotas(d.aliquotasSimples()) : new LinkedHashMap<>(), documento, cct,
                texto(d.observacoes(), 2000), null, true, null, null, dadosCadastrais(d.dadosCadastrais(), cnpj));
    }

    /** Cadastro público (vindo da consulta de CNPJ): precisa ser do mesmo CNPJ e ter tamanho razoável. */
    private static Map<String, Object> dadosCadastrais(Map<String, Object> m, String cnpj) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        if (!cnpj.equals(normalizarCnpj(String.valueOf(m.get("cnpj"))))) {
            throw new ErroNegocio("Os dados consultados são de outro CNPJ. Consulte de novo antes de salvar.");
        }
        if (String.valueOf(m).length() > 200_000) {
            throw new ErroNegocio("Dados cadastrais grandes demais para guardar.");
        }
        return m;
    }

    /** Alíquotas do Simples por anexo: percentuais de 0 a 100 com até 6 casas, gravados com ponto decimal. */
    private static Map<String, Map<String, String>> aliquotas(Map<String, Map<String, String>> entrada) {
        Map<String, Map<String, String>> r = new LinkedHashMap<>();
        if (entrada == null) {
            return r;
        }
        for (String anexo : List.of("I", "II", "III", "IV", "V")) {
            Map<String, String> valores = entrada.get(anexo);
            if (valores == null) {
                continue;
            }
            Map<String, String> limpos = new LinkedHashMap<>();
            for (Map.Entry<String, String> v : valores.entrySet()) {
                if (!CAMPOS_ALIQUOTA.contains(v.getKey()) || v.getValue() == null || v.getValue().isBlank()) {
                    continue;
                }
                String n = v.getValue().trim().replace(',', '.');
                BigDecimal p;
                try {
                    p = new BigDecimal(n);
                } catch (NumberFormatException e) {
                    throw new ErroNegocio("Alíquota inválida no Anexo " + anexo + ": " + v.getValue() + ".");
                }
                if (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0 || Math.max(0, p.scale()) > 6) {
                    throw new ErroNegocio("Alíquota do Anexo " + anexo + " fora do intervalo (0 a 100%, até 6 casas): " + v.getValue() + ".");
                }
                limpos.put(v.getKey(), p.toPlainString());
            }
            if (!limpos.isEmpty()) {
                r.put(anexo, limpos);
            }
        }
        entrada.keySet().stream().filter(k -> !ANEXOS.contains(k)).findFirst()
                .ifPresent(k -> { throw new ErroNegocio("Anexo do Simples inválido: " + k + "."); });
        return r;
    }

    private static String texto(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new ErroNegocio("Texto acima de " + max + " caracteres.");
        }
        return t;
    }

    private static String limitar(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String ufValida(String uf) {
        if (uf == null) {
            return null;
        }
        String u = uf.trim().toUpperCase(Locale.ROOT);
        return UFS.contains(u) ? u : null;
    }

    /** Só letras e dígitos, em maiúsculas (aceita o CNPJ com pontuação e o CNPJ alfanumérico). */
    public static String normalizarCnpj(String cnpj) {
        return cnpj == null ? null : cnpj.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * CNPJ com 14 posições e dígitos verificadores corretos. Vale para o CNPJ numérico e para o alfanumérico
     * (IN RFB 2.229/2024): cada caractere conta pelo código ASCII menos 48, com os mesmos pesos do módulo 11.
     */
    public static boolean cnpjValido(String cnpj) {
        if (cnpj == null || !cnpj.matches("[0-9A-Z]{12}\\d{2}") || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        return digito(cnpj, 12) == cnpj.charAt(12) - '0' && digito(cnpj, 13) == cnpj.charAt(13) - '0';
    }

    private static int digito(String cnpj, int tamanho) {
        int soma = 0;
        int peso = 2;
        for (int i = tamanho - 1; i >= 0; i--) {
            soma += (cnpj.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    static String formatarCnpj(String c) {
        return c != null && c.length() == 14
                ? c.substring(0, 2) + "." + c.substring(2, 5) + "." + c.substring(5, 8) + "/" + c.substring(8, 12) + "-" + c.substring(12)
                : c;
    }
}
