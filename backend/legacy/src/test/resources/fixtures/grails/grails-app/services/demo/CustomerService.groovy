package demo

class CustomerService {

    def findByLastName(String name) {
        return Customer.findByLastName(name)
    }

    def ping() {
        return 'pong'
    }

    def listAll() {
        return Customer.list()
    }
}
