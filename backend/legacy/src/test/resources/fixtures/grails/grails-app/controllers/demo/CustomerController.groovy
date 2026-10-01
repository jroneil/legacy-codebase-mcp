package demo

class CustomerController {

    // Grails convention: the property name resolves to CustomerService
    def customerService

    // explicit declared type
    CustomerService explicitService

    def index() {
        render view: 'index'
    }

    def ping() {
        customerService.ping()
    }

    def show(Long id) {
        def customer = customerService.findByLastName('Smith')
        render view: 'show', model: [customer: customer]
    }
}
