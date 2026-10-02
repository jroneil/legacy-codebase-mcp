package demo;
public class CustomerServiceImpl implements CustomerService {
    private CustomerDAO customerDAO;
    public void setCustomerDAO(CustomerDAO customerDAO) { this.customerDAO = customerDAO; }
    public void find(String id) { customerDAO.find(id); }
    public void create(String name) { customerDAO.insert(name); }
}
