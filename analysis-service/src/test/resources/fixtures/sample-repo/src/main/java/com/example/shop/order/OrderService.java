package com.example.shop.order;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Order find(UUID id) {
        return orderRepository.findById(id).orElseThrow();
    }

    @Transactional
    public Order place(String customerEmail, BigDecimal total) {
        return orderRepository.save(new Order(customerEmail, total));
    }

    /**
     * Refunds part or all of a paid order. The total refunded can never exceed what the customer paid.
     */
    @Transactional
    public Order refund(UUID orderId, RefundRequest request) {
        Order order = find(orderId);
        if (order.getStatus() != Order.Status.PAID) {
            throw new IllegalStateException("Only paid orders can be refunded, this one is " + order.getStatus());
        }
        BigDecimal refundable = order.getTotal().subtract(order.getRefundedAmount());
        if (request.amount().compareTo(refundable) > 0) {
            throw new IllegalArgumentException("Refund of " + request.amount() + " exceeds the refundable " + refundable);
        }
        order.markRefunded(request.amount());
        return order;
    }
}
