package demo;
import java.util.List;
public class Service extends Base implements Worker {
    private Long count;
    public static class Nested { public void nested() {} }
    @Override public String run(Long id) { return ping(1); }
    public String ping(int number) { return "int"; }
    public String ping(String text) { return text.trim(); }
    public void missingCall() { absent("fixture-secret-must-not-escape"); }
}
