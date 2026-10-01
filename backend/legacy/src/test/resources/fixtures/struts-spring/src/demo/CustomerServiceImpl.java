package demo;
public class CustomerServiceImpl implements CustomerService {
    private CustomerDAO dao;
    public CustomerServiceImpl(CustomerDAO dao) { this.dao = dao; }
    public void search() { dao.find(); }
}
