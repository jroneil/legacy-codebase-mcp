package demo;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.hibernate.Session;
public class CustomerDAO {
    private final JdbcTemplate template = null;
    private final Session session = null;
    private static final String INSERT = "INSERT INTO CUSTOMER(id) " + "VALUES (?)";
    public void prepared(Connection connection) throws Exception {
        PreparedStatement ps = connection.prepareStatement(SqlConstants.FIND);
        ps.executeQuery();
    }
    public void insert(Connection connection) throws Exception { connection.prepareStatement(INSERT).executeUpdate(); }
    public void update() { template.update("UPDATE CUSTOMER SET name=? WHERE id=?", "name", 1); }
    public void delete(Statement statement) throws Exception { statement.executeUpdate("DELETE FROM CUSTOMER WHERE id=?"); }
    public void merge() { template.update("MERGE INTO CUSTOMER c USING CUSTOMER_STAGE s ON (c.id=s.id) WHEN MATCHED THEN UPDATE SET c.name=s.name WHEN NOT MATCHED THEN INSERT(id,name) VALUES(s.id,s.name)"); }
    public void copy() { template.update("INSERT INTO CUSTOMER_ARCHIVE(id) SELECT id FROM CUSTOMER"); }
    public void lock(Connection connection) throws Exception { connection.prepareStatement("SELECT * FROM CUSTOMER FOR UPDATE").executeQuery(); }
    public void procedure(Connection connection) throws Exception { CallableStatement call = connection.prepareCall("{call CRM.REFRESH_CUSTOMER(?)}"); call.execute(); }
    public void dynamic(String tableName) { template.queryForList("SELECT * FROM " + tableName + " WHERE id=?", 1); }
    public void malformed() { template.queryForList("SELECT FROM ???"); }
    public void vendor() { template.queryForList("LOCKING TABLE CUSTOMER FOR ACCESS SELECT * FROM CUSTOMER"); }
    public void oracle() { template.queryForList("SELECT c.id FROM CUSTOMER c, ADDRESS a WHERE c.id=a.customer_id(+)"); }
    public void hql() { session.createQuery("from Customer c where c.id = :id").list(); }
    public void named() { session.getNamedQuery("Customer.find").list(); }
    public void nativeQuery() { session.createSQLQuery("SELECT * FROM CUSTOMER").list(); }
}
