package dev.nightshift.orders;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class OrderController {

    private final OrderService service;
    private final OrderRepository orders;

    public OrderController(OrderService service, OrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderRepository.Order> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderRepository.Order> get(@PathVariable long id) {
        return orders.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(OrderService.OrderFailedException.class)
    ResponseEntity<Map<String, Object>> failed(OrderService.OrderFailedException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        if (e.orderId() != null) {
            body.put("orderId", e.orderId());
        }
        return ResponseEntity.status(e.status()).body(body);
    }
}
