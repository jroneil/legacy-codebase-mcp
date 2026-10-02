package demo;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "CUSTOMER")
public class Customer {
    private Long id;
}
