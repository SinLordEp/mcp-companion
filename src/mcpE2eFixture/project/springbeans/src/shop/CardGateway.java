package shop;

import org.springframework.stereotype.Component;

@Component("cardGateway")
public class CardGateway implements PaymentGateway {
    @Override
    public void pay(int cents) {
    }
}
