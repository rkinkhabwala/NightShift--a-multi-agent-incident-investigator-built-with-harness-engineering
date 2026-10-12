package dev.nightshift.payments;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChargeController {

    private final ChargeService service;

    public ChargeController(ChargeService service) {
        this.service = service;
    }

    @PostMapping("/charges")
    public ResponseEntity<ChargeService.Charge> charge(@Valid @RequestBody ChargeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.charge(request));
    }

    @ExceptionHandler(ChargeService.ChargeFailedException.class)
    ResponseEntity<Map<String, String>> failed(ChargeService.ChargeFailedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", String.valueOf(e.getMessage())));
    }
}
