package com.rikkei.orderservice.controller;

import com.rikkei.orderservice.dto.OrderRequestDTO;
import com.rikkei.orderservice.dto.ProductResponseDTO;
import com.rikkei.orderservice.model.Order;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final RestTemplate restTemplate;

    private final Map<Long, Order> orderRepository = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    private static final String PRODUCT_SERVICE_URL = "http://PRODUCT-SERVICE/api/v1/products/";

    @PostConstruct
    public void initData() {
        Order o1 = Order.builder()
                .id(idCounter.getAndIncrement())
                .customerId(1L)
                .productId(1L)
                .quantity(1)
                .totalPrice(new BigDecimal("35000000"))
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
        orderRepository.put(o1.getId(), o1);
    }

    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(new ArrayList<>(orderRepository.values()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        Order order = orderRepository.get(id);
        if (order != null) {
            return ResponseEntity.ok(order);
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody OrderRequestDTO request) {
        log.info(">>> [ORDER-SERVICE] Bắt đầu gọi PRODUCT-SERVICE qua URL cân bằng tải: {}{}", PRODUCT_SERVICE_URL, request.getProductId());

        ProductResponseDTO product = restTemplate.getForObject(PRODUCT_SERVICE_URL + request.getProductId(), ProductResponseDTO.class);

        if (product == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        log.info(">>> [ORDER-SERVICE] Đã nhận phản hồi từ PRODUCT-SERVICE (Instance chạy ở Port: {})", product.getServerPort());

        BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
        Long newId = idCounter.getAndIncrement();

        Order order = Order.builder()
                .id(newId)
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .totalPrice(totalPrice)
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build();

        orderRepository.put(newId, order);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping("/test-load-balancing")
    public ResponseEntity<Map<String, Object>> testLoadBalancing(@RequestParam(defaultValue = "10") int count) {
        log.info(">>> Bắt đầu gửi {} requests kiểm tra Client-Side Load Balancing...", count);

        Map<String, Integer> portDistribution = new HashMap<>();
        List<Map<String, String>> requestLogs = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            try {
                ProductResponseDTO response = restTemplate.getForObject(PRODUCT_SERVICE_URL + "1", ProductResponseDTO.class);
                String servedPort = (response != null && response.getServerPort() != null) ? response.getServerPort() : "unknown";
                portDistribution.put(servedPort, portDistribution.getOrDefault(servedPort, 0) + 1);

                Map<String, String> logEntry = new LinkedHashMap<>();
                logEntry.put("requestNumber", String.valueOf(i));
                logEntry.put("handledByPort", servedPort);
                logEntry.put("productName", response != null ? response.getName() : "null");
                requestLogs.add(logEntry);
            } catch (Exception e) {
                log.error("Lỗi ở request {}: {}", i, e.getMessage());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", count);
        summary.put("algorithm", "Round-Robin (Spring Cloud LoadBalancer)");
        summary.put("distributionStatistics", portDistribution);
        summary.put("executionDetails", requestLogs);

        return ResponseEntity.ok(summary);
    }
}
