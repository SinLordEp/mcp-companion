package shop;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Constructor injection: the repository, and the card gateway chosen by its qualifier. */
@Service
public class OrderService {
    private final OrderRepository repository;
    private final PaymentGateway gateway;

    public OrderService(OrderRepository repository, @Qualifier("cardGateway") PaymentGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }
}
