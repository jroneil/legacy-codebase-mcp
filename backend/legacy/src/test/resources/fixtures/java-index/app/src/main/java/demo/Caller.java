package demo;
import other.Service;
public class Caller {
    public String invoke(Service service) { return service.ping(7); }
}
