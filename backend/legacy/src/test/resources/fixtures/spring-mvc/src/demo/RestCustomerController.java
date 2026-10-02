package demo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping(value = "/api/customers")
public class RestCustomerController {
    @GetMapping(path = "/status")
    public String status() { return "ok"; }
}
