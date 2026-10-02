package demo;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customers")
public class CustomerController {
    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{id}")
    public Customer get(Long id) {
        return customerService.find(id);
    }

    @PostMapping
    public Customer create(Customer customer) {
        return customerService.save(customer);
    }
}
