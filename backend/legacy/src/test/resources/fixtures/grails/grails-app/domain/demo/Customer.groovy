package demo

class Customer {

    String lastName

    static mapping = {
        table 'CUSTOMER'
    }

    static hasMany = [orders: CustomerOrder]
}
