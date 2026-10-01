package demo

class CustomerService {

    static transactional = true

    def findByLastName(String lastName) {
        return Customer.findByLastName(lastName)
    }

    def findAllCustomers() {
        return Customer.findAll()
    }
}
