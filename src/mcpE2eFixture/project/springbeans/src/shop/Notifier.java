package shop;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Field and setter injection; one field with two candidates, one with none. */
@Component
public class Notifier {
    @Autowired
    private AuditLog auditLog;

    @Autowired
    private PaymentGateway gateway;

    @Autowired(required = false)
    private Refunds refunds;

    private Clock clock;

    @Autowired
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
