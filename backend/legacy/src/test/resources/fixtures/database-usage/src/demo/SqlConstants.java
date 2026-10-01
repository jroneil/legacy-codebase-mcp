package demo;
public class SqlConstants {
    public static final String FIND = "SELECT c.id FROM CUSTOMER c JOIN ADDRESS a ON c.id=a.customer_id WHERE c.password='fixture-secret-must-not-escape'";
}
