package com.example.shop.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PAID, REFUNDED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String customerEmail;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PAID;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Order() {
    }

    public Order(String customerEmail, BigDecimal total) {
        this.customerEmail = customerEmail;
        this.total = total;
    }

    void markRefunded(BigDecimal amount) {
        refundedAmount = refundedAmount.add(amount);
        if (refundedAmount.compareTo(total) == 0) {
            status = Status.REFUNDED;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
