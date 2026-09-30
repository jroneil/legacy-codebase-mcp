package legacy;
import unavailable.Library;
public class Missing extends unavailable.Base {
    Library library;
    @Override public void inherited() { library.execute(); }
    public void overload(Library value) { value.execute(); }
    public void overload(String value) { value.trim(); }
}
