package br.com.escritorio.hubfiscal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Datas nos parâmetros das URLs no formato ISO (AAAA-MM-DD). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        DateTimeFormatterRegistrar r = new DateTimeFormatterRegistrar();
        r.setUseIsoFormat(true);
        r.registerFormatters(registry);
    }
}
