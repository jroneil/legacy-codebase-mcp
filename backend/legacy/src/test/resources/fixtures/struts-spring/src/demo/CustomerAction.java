package demo;
public class CustomerAction extends org.apache.struts.action.Action {
    private CustomerService service;
    public void setService(CustomerService service) { this.service = service; }
}
