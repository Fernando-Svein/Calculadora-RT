package br.com.escritorio.hubfiscal.importacao;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import br.com.escritorio.hubfiscal.config.ErroHandler.ErroNegocio;
import br.com.escritorio.hubfiscal.importacao.SpedParser.ResultadoSped;

/**
 * Recebe os arquivos enviados pelo usuário (SPED .txt, XML, ou ZIP com
 * qualquer combinação deles) e devolve tudo o que foi lido.
 */
@Service
public class ImportacaoService {

    private static final long LIMITE_DESCOMPACTADO = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final int LIMITE_ARQUIVOS = 200_000;

    /** Tudo o que foi lido de um lote de arquivos. */
    public static class Importacao {
        public final List<ResultadoSped> speds = new ArrayList<>();
        public final List<DocumentoFiscal> documentos = new ArrayList<>();
        /** Resumo por arquivo: nome → o que foi encontrado. */
        public final Map<String, String> arquivos = new LinkedHashMap<>();
        public final List<String> avisos = new ArrayList<>();
        long bytesLidos;
        int quantidadeArquivos;

        public List<ItemFiscal> cadastroSped() {
            List<ItemFiscal> r = new ArrayList<>();
            speds.forEach(s -> r.addAll(s.cadastro()));
            return r;
        }

        public List<ItemFiscal> movimentosSped() {
            List<ItemFiscal> r = new ArrayList<>();
            speds.forEach(s -> r.addAll(s.movimentos()));
            return r;
        }

        public List<ItemFiscal> itensXml() {
            List<ItemFiscal> r = new ArrayList<>();
            documentos.forEach(d -> r.addAll(d.itens));
            return r;
        }
    }

    public Importacao importar(List<MultipartFile> arquivos) {
        if (arquivos == null || arquivos.isEmpty()) {
            throw new ErroNegocio("Nenhum arquivo enviado.");
        }
        Importacao imp = new Importacao();
        for (MultipartFile f : arquivos) {
            if (f.isEmpty()) {
                imp.arquivos.put(nome(f), "Arquivo vazio – ignorado");
                continue;
            }
            try (InputStream in = f.getInputStream()) {
                processar(imp, nome(f), in.readAllBytes(), 0);
            } catch (IOException e) {
                imp.avisos.add(nome(f) + ": não foi possível ler (" + e.getMessage() + ")");
            }
        }
        if (imp.speds.isEmpty() && imp.documentos.isEmpty()) {
            throw new ErroNegocio("Nenhum SPED ou XML fiscal válido foi encontrado nos arquivos enviados. "
                    + String.join(" | ", imp.arquivos.entrySet().stream().limit(5)
                            .map(e -> e.getKey() + ": " + e.getValue()).toList()));
        }
        return imp;
    }

    private static String nome(MultipartFile f) {
        return f.getOriginalFilename() == null ? "arquivo" : f.getOriginalFilename();
    }

    private void processar(Importacao imp, String nome, byte[] conteudo, int profundidade) throws IOException {
        imp.bytesLidos += conteudo.length;
        imp.quantidadeArquivos++;
        if (imp.bytesLidos > LIMITE_DESCOMPACTADO || imp.quantidadeArquivos > LIMITE_ARQUIVOS) {
            throw new ErroNegocio("Volume de arquivos acima do limite (2 GB ou 200 mil arquivos). Divida o envio.");
        }
        if (ehZip(conteudo)) {
            if (profundidade > 3) {
                imp.arquivos.put(nome, "ZIP aninhado demais – ignorado");
                return;
            }
            int n = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(conteudo))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    byte[] dados = lerLimitado(zip);
                    processar(imp, nome + "/" + e.getName(), dados, profundidade + 1);
                    n++;
                }
            }
            imp.arquivos.put(nome, "ZIP com " + n + " arquivo(s)");
            return;
        }
        if (SpedParser.pareceSped(conteudo)) {
            ResultadoSped s = SpedParser.ler(conteudo);
            imp.speds.add(s);
            imp.arquivos.put(nome, s.tipo() + " de " + s.nome() + " (" + s.dataInicial() + " a " + s.dataFinal()
                    + "): " + s.cadastro().size() + " itens no 0200, " + s.movimentos().size() + " movimentos");
            return;
        }
        if (XmlFiscalParser.pareceXml(conteudo)) {
            String[] motivo = new String[1];
            DocumentoFiscal d = XmlFiscalParser.ler(nome, conteudo, motivo);
            if (d == null) {
                imp.arquivos.put(nome, motivo[0]);
            } else {
                imp.documentos.add(d);
                imp.arquivos.put(nome, d.origem + " nº " + d.numero + " – " + d.itens.size() + " item(ns)");
            }
            return;
        }
        imp.arquivos.put(nome, "Formato não reconhecido – ignorado");
    }

    private static boolean ehZip(byte[] c) {
        return c.length > 4 && c[0] == 'P' && c[1] == 'K' && c[2] == 3 && c[3] == 4;
    }

    private static byte[] lerLimitado(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 500L * 1024 * 1024) {
                throw new ErroNegocio("Arquivo dentro do ZIP maior que 500 MB.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
