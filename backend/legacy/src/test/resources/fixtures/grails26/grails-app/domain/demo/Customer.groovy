package demo

class Customer {

    String firstName
    String lastName

    static mapping = {
        table 'CUSTOMER'
        id column: 'customer_id', generator: 'identity'
        version false
    }

    static constraints = {
        lastName blank: false
        firstName nullable: true
    }
}
