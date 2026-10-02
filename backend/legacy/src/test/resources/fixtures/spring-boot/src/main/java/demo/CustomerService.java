package demo;

import jakarta.annotation.Resource;
import jakarta.inject.Inject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {
    private final CustomerRepository repository;

    @Inject
    private AuditService auditService;

    @Resource(name = "legacyClient")
    private CustomerClient customerClient;

    @Autowired
    private Notifier notifier;

    @Autowired
    private MissingGateway missingGateway;

    private ClockService clockService;

    @Autowired
    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Autowired
    public void setClockService(ClockService clockService) {
        this.clockService = clockService;
    }

    public Customer find(Long id) {
        auditService.record();
        return repository.findById(id);
    }

    public Customer save(Customer customer) {
        customerClient.prepare(customer);
        return repository.save(customer);
    }
}
