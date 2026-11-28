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

    /** Parâmetro enviado pela tela fora do esperado, com mensagem escrita para o usuário (HTTP 400). */
    public static class ErroParametro extends RuntimeException {
        public ErroParametro(String mensagem) {
            super(mensagem);
        }
    }

    @ExceptionHandler(ErroParametro.class)
    public ProblemDetail parametroInvalido(ErroParametro e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("Parâmetro inválido");
        return p;
    }

    @ExceptionHandler(ErroNegocio.class)
    public ProblemDetail negocio(ErroNegocio e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        p.setTitle("Não foi possível processar");
        return p;
    }

    /**
     * Valor inválido detectado no próprio hub. A mensagem técnica (pode citar classes ou enums internos) fica no log;
     * a tela recebe um texto fixo.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail argumento(IllegalArgumentException e) {
        log.warn("Parâmetro inválido: {}", e.getMessage());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Um dos valores enviados é inválido. Confira os campos e tente novamente.");
        p.setTitle("Parâmetro inválido");
        return p;
    }

    /** Parâmetro com formato errado (ex.: data inválida) ou obrigatório ausente. */
    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.multipart.MultipartException.class })
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

    /**
     * Erros do próprio Spring com status definido (método não permitido 405, tipo de conteúdo 415, 404...):
     * mantém o status correto em vez de transformá-los em erro interno.
     */
    @ExceptionHandler(org.springframework.web.ErrorResponseException.class)
    public ProblemDetail respostaSpring(org.springframework.web.ErrorResponseException e) {
        return e.getBody();
    }

    @ExceptionHandler({ org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class })
    public ProblemDetail requisicaoInvalida(Exception e) {
        return ((org.springframework.web.ErrorResponse) e).getBody();
    }

    /**
     * Falha inesperada: a tela recebe só um código de referência; o detalhe técnico (classe, mensagem,
     * caminhos de arquivo) fica no log do servidor, onde o código permite localizá-lo.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail geral(Exception e) {
        String codigo = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("Erro inesperado no hub [ref {}]", codigo, e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro inesperado. Se persistir, informe ao administrador o código " + codigo + ".");
        p.setTitle("Erro interno do hub");
        return p;
    }
}
