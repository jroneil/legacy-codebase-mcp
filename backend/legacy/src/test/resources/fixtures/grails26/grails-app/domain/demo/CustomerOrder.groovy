package demo

class CustomerOrder {

    String reference
    static belongsTo = [customer: Customer]
}
