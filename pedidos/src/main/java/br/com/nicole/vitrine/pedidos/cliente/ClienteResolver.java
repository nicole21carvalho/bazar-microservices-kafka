package br.com.nicole.vitrine.pedidos.cliente;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Descobre o cliente da requisicao. Fase 1: cabecalhos X-Cliente-Id e
 * X-Cliente-Email, so para os testes do fluxo de eventos. Na fase de seguranca,
 * passa a ler o token JWT validado (claims "sub" e "email").
 */
@Component
public class ClienteResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return parametro.getParameterType().equals(Cliente.class);
    }

    @Override
    public Cliente resolveArgument(
            MethodParameter parametro, ModelAndViewContainer mav, NativeWebRequest requisicao, WebDataBinderFactory f) {
        String id = requisicao.getHeader("X-Cliente-Id");
        String email = requisicao.getHeader("X-Cliente-Email");
        if (id == null || id.isBlank() || email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cliente não identificado");
        }
        return new Cliente(id, email);
    }
}
