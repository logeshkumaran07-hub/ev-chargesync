package com.hackathon.chargesync;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice(basePackages = "com.hackathon.chargesync")
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        String accept = request.getHeader(HttpHeaders.ACCEPT);

        if (status.value() == HttpStatus.UNAUTHORIZED.value()
                && accept != null
                && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_HTML_VALUE)) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create("/login.html"))
                    .build();
        }

        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = status.is4xxClientError()
                    ? "The request could not be completed."
                    : "The server could not complete the request.";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.toString());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
