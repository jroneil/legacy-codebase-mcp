package demo;
public class CustomerDAO {
    public void find(String id) {
        try (java.sql.Connection connection = null) {
            var statement = connection.prepareStatement("SELECT * FROM CUSTOMER WHERE ID = ?");
            statement.executeQuery();
        } catch (Exception ignored) { }
    }
    public void insert(String name) {
        try (java.sql.Connection connection = null) {
            var statement = connection.prepareStatement("INSERT INTO CUSTOMER (NAME) VALUES (?)");
            statement.executeUpdate();
        } catch (Exception ignored) { }
    }
}
