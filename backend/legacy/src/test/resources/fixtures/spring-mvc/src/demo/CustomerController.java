package demo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
@Controller
@RequestMapping(RouteConstants.CUSTOMERS)
public class CustomerController {
    private CustomerServiceImpl customerService;
    public void setCustomerService(CustomerServiceImpl customerService) { this.customerService = customerService; }

    @GetMapping(RouteConstants.BY_ID)
    public String getCustomer(String id) {
        customerService.find(id);
        return "customer/detail";
    }

    @PostMapping
    public String create(String name) {
        customerService.create(name);
        return "customer/created";
    }

    @RequestMapping(path = "/all")
    public String all() { return "customer/list"; }

    @RequestMapping(value = {"/mapped-a", "/mapped-b"}, method = {RequestMethod.GET, RequestMethod.POST})
    public String explicitlyMapped() { return "customer/list"; }

    @GetMapping(path = {"/a", "/b"})
    public String aliases() { return "customer/list"; }

    @PutMapping("/{id}") public String update(String id) { return "customer/updated"; }
    @DeleteMapping("/{id}") public String delete(String id) { return "customer/deleted"; }
    @PatchMapping("/{id}") public String patch(String id) { return "customer/patched"; }

    @ResponseBody
    @GetMapping("/raw")
    public String raw() { return "raw-body"; }

    @GetMapping(dynamicPath())
    public String dynamic() { return "customer/dynamic"; }
    private static String dynamicPath() { return "/dynamic"; }
}
