package br.com.escritorio.hubfiscal.sistema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;

/**
 * Atualização das calculadoras pedida pelo administrador (botão "Atualizar agora" da Situação do sistema).
 * <p>
 * O hub não atualiza nada por conta própria: a atualização é a mesma verificação diária dos scripts
 * ({@code hub.ps1 verificar} / {@code hub.sh verificar}), que para e religa os serviços. No Linux o hub roda sem
 * privilégios; ele só grava {@code logs/pedido-atualizacao.json} e uma unidade {@code .path} do systemd (root) inicia a
 * verificação. No Windows o hub inicia o script numa janela oculta. Enquanto roda, o script mantém
 * {@code logs/atualizacao-em-andamento.json}; ao terminar, grava {@code logs/ultima-atualizacao.json}.
 */
@Service
public class AtualizacaoManual {

    private static final Logger log = LoggerFactory.getLogger(AtualizacaoManual.class);
    /** Pedido que ninguém atendeu nesse prazo (ex.: gatilho do systemd ausente) deixa de bloquear um novo. */
    static final Duration PEDIDO_EXPIRA = Duration.ofMinutes(5);
    /** Verificação "em andamento" mais antiga que isso é considerada interrompida (o script tem limite de 2 h). */
    static final Duration ANDAMENTO_EXPIRA = Duration.ofHours(3);

    private final Path pasta;
    private final Path pedido;
    private final Path emAndamento;
    private final String modo;

    public AtualizacaoManual(HubProperties props) {
        Path p = Path.of(props.arquivoUltimaAtualizacao()).toAbsolutePath().getParent();
        this.pasta = p == null ? Path.of("").toAbsolutePath() : p;
        this.pedido = pasta.resolve("pedido-atualizacao.json");
        this.emAndamento = pasta.resolve("atualizacao-em-andamento.json");
        this.modo = props.atualizacaoManual().trim().toLowerCase(Locale.ROOT);
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public boolean disponivel() {
        return !"desativada".equals(modo);
    }

    /** Situação para a tela: pedido pendente e verificação em andamento (datas ISO) ou null. */
    public Map<String, Object> situacao() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponivel", disponivel());
        m.put("pedidoEm", vigente(pedido, PEDIDO_EXPIRA));
        m.put("emAndamentoDesde", vigente(emAndamento, ANDAMENTO_EXPIRA));
        return m;
    }

    /** Registra o pedido (e no Windows inicia o script). Recusa se já houver uma verificação pedida ou rodando. */
    public synchronized Map<String, Object> solicitar(String quem) {
        if (!disponivel()) {
            throw new ErroNegocio("A atualização pela tela está desativada neste servidor (hub.atualizacao-manual).");
        }
        if (vigente(emAndamento, ANDAMENTO_EXPIRA) != null) {
            throw new ErroNegocio("Já há uma atualização em andamento. Aguarde terminar.");
        }
        if (vigente(pedido, PEDIDO_EXPIRA) != null) {
            throw new ErroNegocio("A atualização já foi solicitada e deve começar em instantes.");
        }
        LocalDateTime agora = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String json = "{\"pedidoEm\": \"" + agora + "\", \"por\": \"" + quem.replaceAll("[\"\\\\\\p{Cntrl}]", "") + "\"}\n";
        try {
            Files.createDirectories(pasta);
            Path tmp = pasta.resolve("pedido-atualizacao.json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, pedido, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível registrar o pedido de atualização em " + pasta + ": " + e.getMessage(), e);
        }
        if ("auto".equals(modo) && windows()) {
            iniciarScriptWindows();
        }
        log.info("Atualização das calculadoras solicitada pela tela por {}", quem);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pedidoEm", agora.toString());
        r.put("mensagem", "Atualização solicitada. O servidor consulta o GitHub da Receita e, se houver versão nova, atualiza e religa os serviços.");
        return r;
    }

    /** Windows: inicia {@code scripts\hub.ps1 verificar} desvinculado do hub (o script pode parar e religar o hub). */
    private void iniciarScriptWindows() {
        Path raiz = Path.of("").toAbsolutePath().getParent(); // o hub roda na pasta hub-fiscal
        Path script = raiz == null ? null : raiz.resolve("scripts").resolve("hub.ps1");
        if (script == null || !Files.isRegularFile(script)) {
            apagar(pedido);
            throw new ErroNegocio("Script de atualização não encontrado (scripts\\hub.ps1). Use atualizar-calculadoras.bat no servidor.");
        }
        try {
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(), "verificar")
                    .directory(raiz.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(pasta.resolve("atualizacao-manual.log").toFile()))
                    .start();
        } catch (IOException e) {
            apagar(pedido);
            throw new IllegalStateException("Não foi possível iniciar o script de atualização: " + e.getMessage(), e);
        }
    }

    /** Data do arquivo (campo pedidoEm/desde ou data de modificação) se ele existe e não expirou; senão null. */
    private static String vigente(Path arquivo, Duration validade) {
        try {
            if (!Files.isRegularFile(arquivo)) {
                return null;
            }
            LocalDateTime quando = LocalDateTime.ofInstant(Files.getLastModifiedTime(arquivo).toInstant(), java.time.ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.SECONDS);
            return quando.plus(validade).isAfter(LocalDateTime.now()) ? quando.toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void apagar(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // nada a fazer
        }
    }
}
