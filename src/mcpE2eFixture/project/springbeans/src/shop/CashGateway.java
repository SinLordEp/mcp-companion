package shop;

import org.springframework.stereotype.Component;

@Component
public class CashGateway implements PaymentGateway {
    @Override
    public void pay(int cents) {
    }
}
