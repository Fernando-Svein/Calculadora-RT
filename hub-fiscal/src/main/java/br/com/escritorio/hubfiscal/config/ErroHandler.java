package br.com.escritorio.hubfiscal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Converte erros do hub em respostas legíveis pelas telas (RFC 7807). */
@RestControllerAdvice
public class ErroHandler {

    private static final Logger log = LoggerFactory.getLogger(ErroHandler.class);

    /** Erro previsível causado pela entrada do usuário (arquivo inválido, parâmetro errado...). */
    public static class ErroNegocio extends RuntimeException {
        public ErroNegocio(String mensagem) {
            super(mensagem);
        }
    }

    @ExceptionHandler(ErroNegocio.class)
    public ProblemDetail negocio(ErroNegocio e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        p.setTitle("Não foi possível processar");
        return p;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail argumento(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("Parâmetro inválido");
        return p;
    }

    /** Parâmetro com formato errado (ex.: data inválida) ou obrigatório ausente. */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, org.springframework.http.converter.HttpMessageNotReadableException.class })
    public ProblemDetail parametro(Exception e) {
        String detalhe;
        if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            detalhe = "Os dados enviados estão em formato inválido.";
        } else if (e instanceof MethodArgumentTypeMismatchException m) {
            boolean data = m.getRequiredType() != null && java.time.temporal.Temporal.class.isAssignableFrom(m.getRequiredType());
            detalhe = data
                    ? "Data inválida em '" + m.getName() + "': " + m.getValue() + ". Use o formato AAAA-MM-DD (ex.: 2026-11-24)."
                    : "Valor inválido em '" + m.getName() + "': " + m.getValue() + ".";
        } else if (e instanceof MissingServletRequestParameterException m) {
            detalhe = "Parâmetro obrigatório ausente: " + m.getParameterName() + ".";
        } else {
            detalhe = "Nenhum arquivo foi enviado.";
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalhe);
        p.setTitle("Parâmetro inválido");
        return p;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tamanho(MaxUploadSizeExceededException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Os arquivos enviados excedem o tamanho máximo permitido. Envie em partes ou compacte em ZIP.");
        p.setTitle("Arquivo muito grande");
        return p;
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail naoEncontrado(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso não encontrado.");
        p.setTitle("Não encontrado");
        return p;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail geral(Exception e) {
        log.error("Erro inesperado no hub", e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erro inesperado: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        p.setTitle("Erro interno do hub");
        return p;
    }
}
