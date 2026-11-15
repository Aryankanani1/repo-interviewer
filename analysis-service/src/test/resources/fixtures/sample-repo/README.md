# Shop

A small order service built with Spring Boot: place orders, look them up, and refund them in full or in part.

## Run

Start PostgreSQL on localhost:5432 with a `shop` database, then:

    mvn spring-boot:run

Flyway creates the schema on startup.

## API

- `GET /orders/{id}` returns one order.
- `POST /orders/{id}/refund` with `{"amount": 10.00}` refunds part of a paid order. The total refunded can never exceed what the customer paid.
