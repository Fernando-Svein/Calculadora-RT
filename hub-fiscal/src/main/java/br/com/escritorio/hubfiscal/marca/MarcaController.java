package br.com.escritorio.hubfiscal.marca;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.config.HubProperties;

/**
 * Logo do escritório exibida no cabeçalho, na tela de login e na aba do navegador.
 *
 * <p>O administrador envia a imagem pela tela "Logo do escritório" ({@code /admin/**}: só administradores).
 * A leitura ({@code /marca}, {@code /marca/logo}) é pública porque a tela de login também mostra a logo.
 *
 * <p>Segurança: aceita só PNG e JPEG (conferidos pelos bytes iniciais, não pela extensão), recusa SVG
 * (pode conter scripts) e nunca guarda o arquivo recebido: a imagem é decodificada e regravada como um PNG
 * novo, o que descarta metadados e qualquer conteúdo embutido. Imagens grandes são reduzidas.
 */
@RestController
public class MarcaController {

    static final long TAMANHO_MAXIMO = 2L * 1024 * 1024;
    static final int LADO_MAXIMO_ORIGINAL = 6000;
    /** Tamanho máximo guardado: suficiente para a tela de login em telas de alta densidade. */
    static final int LARGURA_GUARDADA = 640;
    static final int ALTURA_GUARDADA = 320;

    private final Path arquivo;

    public MarcaController(HubProperties props) {
        this.arquivo = Path.of(props.arquivoLogo()).toAbsolutePath().normalize();
    }

    /** Se há logo cadastrada e a "versão" (data de alteração) usada pelas telas para não mostrar uma cópia antiga. */
    @GetMapping("/marca")
    public Map<String, Object> estado() throws IOException {
        boolean tem = Files.isRegularFile(arquivo);
        return Map.of("logo", tem, "versao", tem ? Files.getLastModifiedTime(arquivo).toMillis() : 0L);
    }

    @GetMapping("/marca/logo")
    public ResponseEntity<byte[]> logo() throws IOException {
        if (!Files.isRegularFile(arquivo)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(Files.readAllBytes(arquivo));
    }

    @PostMapping("/admin/marca/logo")
    public Map<String, Object> enviar(@RequestParam("arquivo") MultipartFile enviado) throws IOException {
        if (enviado == null || enviado.isEmpty()) {
            throw new ErroNegocio("Selecione um arquivo de imagem.");
        }
        if (enviado.getSize() > TAMANHO_MAXIMO) {
            throw new ErroNegocio("A imagem tem mais de 2 MB. Use um PNG ou JPEG menor.");
        }
        byte[] bytes = enviado.getBytes();
        if (!png(bytes) && !jpeg(bytes)) {
            throw new ErroNegocio("Formato não aceito. Envie a logo em PNG (de preferência com fundo transparente) ou JPEG.");
        }
        BufferedImage original;
        try {
            original = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            original = null;
        }
        if (original == null) {
            throw new ErroNegocio("Não foi possível ler a imagem. O arquivo pode estar corrompido.");
        }
        if (original.getWidth() > LADO_MAXIMO_ORIGINAL || original.getHeight() > LADO_MAXIMO_ORIGINAL) {
            throw new ErroNegocio("A imagem é grande demais (máximo de " + LADO_MAXIMO_ORIGINAL + " pixels de lado).");
        }
        BufferedImage limpa = reduzir(original);
        Files.createDirectories(arquivo.getParent());
        Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
        if (!ImageIO.write(limpa, "png", temporario.toFile())) {
            throw new ErroNegocio("Não foi possível gravar a imagem.");
        }
        Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return estado();
    }

    @DeleteMapping("/admin/marca/logo")
    public Map<String, Object> remover() throws IOException {
        Files.deleteIfExists(arquivo);
        return estado();
    }

    private static boolean png(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean jpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** Copia a imagem para um quadro novo (ARGB), reduzindo para caber em 640 × 320 sem distorcer. */
    static BufferedImage reduzir(BufferedImage img) {
        double escala = Math.min(1.0, Math.min((double) LARGURA_GUARDADA / img.getWidth(), (double) ALTURA_GUARDADA / img.getHeight()));
        int w = Math.max(1, (int) Math.round(img.getWidth() * escala));
        int h = Math.max(1, (int) Math.round(img.getHeight() * escala));
        BufferedImage nova = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = nova.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return nova;
    }
}
